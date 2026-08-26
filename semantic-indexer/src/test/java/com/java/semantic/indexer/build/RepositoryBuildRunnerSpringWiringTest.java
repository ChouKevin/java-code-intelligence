package com.java.semantic.indexer.build;

import com.java.semantic.indexer.config.IndexerBuildConfiguration;
import com.java.semantic.indexer.incremental.ChangedSource;
import com.java.semantic.indexer.incremental.IncrementalIndexPlan;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.SourceContractChangeDetector;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryBuildRunnerSpringWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IndexerBuildConfiguration.class)
            .withBean(ExactRepositoryCheckout.class, () -> mock(ExactRepositoryCheckout.class))
            .withBean(MongoTemplate.class, () -> mongoTemplate())
            .withBean(IndexJobStore.class, () -> mock(IndexJobStore.class))
            .withBean(PublicationPort.class, () -> mock(PublicationPort.class))
            .withBean(JavaSemanticService.class, () -> mock(JavaSemanticService.class))
            .withBean(JdtWorkspaceManager.class, () -> mock(JdtWorkspaceManager.class));

    @Test
    void registers_a_real_job_scoped_build_factory_and_runner() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RepositoryBuildScopeFactory.class);
            assertThat(context).hasSingleBean(RepositoryBuildRunner.BuildScopeFactory.class);
            assertThat(context).hasSingleBean(RepositoryBuildRunner.class);
        });
    }

    @Test
    void closes_jgit_and_invalidates_the_jdt_workspace_when_the_scope_closes() {
        IndexBuildService.CheckoutResolver checkout = ignored -> new IndexBuildService.CheckedOutRepository(
                java.nio.file.Path.of("."), new RepositoryRevision("a".repeat(40)));
        MongoTemplate template = mongoTemplate();
        Git git = mock(Git.class);
        when(git.getRepository()).thenReturn(mock(Repository.class));
        JdtWorkspaceManager workspaces = mock(JdtWorkspaceManager.class);
        RepositoryBuildScopeFactory factory = new RepositoryBuildScopeFactory(checkout, template,
                mock(IndexJobStore.class), mock(PublicationPort.class), mock(JavaSemanticService.class), workspaces,
                root -> git);

        RepositoryBuildRunner.BuildScope scope = factory.open(job());
        scope.close();

        verify(git).close();
        verify(workspaces).invalidate(RepositoryId.of("orders"));
    }

    @Test
    void releases_jgit_and_jdt_resources_when_the_real_build_scope_fails() {
        IndexBuildService.CheckoutResolver checkout = ignored -> new IndexBuildService.CheckedOutRepository(
                java.nio.file.Path.of("."), new RepositoryRevision("a".repeat(40)));
        Git git = mock(Git.class);
        when(git.getRepository()).thenReturn(mock(Repository.class));
        JdtWorkspaceManager workspaces = mock(JdtWorkspaceManager.class);
        RepositoryBuildScopeFactory factory = new RepositoryBuildScopeFactory(checkout, mongoTemplate(),
                mock(IndexJobStore.class), mock(PublicationPort.class), mock(JavaSemanticService.class), workspaces,
                root -> git);

        assertThatThrownBy(() -> new RepositoryBuildRunner(factory).run(job()))
                .isInstanceOf(RuntimeException.class);

        verify(git).close();
        verify(workspaces).invalidate(RepositoryId.of("orders"));
    }

    @Test
    void treats_build_descriptor_changes_as_full_rebuilds_when_module_ownership_is_unknown() {
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner((parent, selected) -> List.of(
                ChangedSource.modify("pom.xml", "<project/>", "<project><version>2</version></project>")),
                SourceContractChangeDetector.lightweight(), RepositoryBuildScopeFactory.conservativeModuleLocator());

        IncrementalIndexPlan plan = planner.plan(new IncrementalIndexPlanner.PublishedIndex(
                List.of("src/main/java/demo/Example.java"), List.of(), Map.of()), "a".repeat(40), "b".repeat(40),
                List.of("src/main/java/demo/Example.java"));

        assertThat(plan.fullRepository()).isTrue();
        assertThat(plan.copyPaths()).isEmpty();
        assertThat(plan.reanalyzePaths()).containsExactly("src/main/java/demo/Example.java");
    }

    private static MongoTemplate mongoTemplate() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getConverter()).thenReturn(mock(MongoConverter.class));
        return template;
    }

    private static IndexJob job() {
        return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), Optional.of(new IndexJobTarget(
                new RepositoryRevision("a".repeat(40)), new GenerationId("g-1"), 1L)), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.BUILD);
    }
}
