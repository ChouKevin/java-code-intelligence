package com.java.semantic.indexer.repository;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactRepositoryCheckoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void checks_out_the_admitted_revision_after_the_default_branch_moves() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRevision movedBranchRevision = RepositoryRevision.ofSha("b".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.resolveRemoteRef("https://example.test/orders.git", "main")).thenReturn(movedBranchRevision);
        when(git.currentRevision(root)).thenReturn(admittedRevision);
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git);

        IndexBuildService.CheckedOutRepository result = checkout.checkout(job(repositoryId, admittedRevision));

        assertThat(result.root()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(result.revision()).isEqualTo(admittedRevision);
        verify(git).fetch(root);
        verify(git).checkoutDetached(root, admittedRevision);
        verify(git, never()).resolveRemoteRef(anyString(), anyString());
    }

    @Test
    void clones_when_the_repository_working_tree_is_absent() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(false);
        when(git.currentRevision(root)).thenReturn(admittedRevision);
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git);

        checkout.checkout(job(repositoryId, admittedRevision));

        verify(git).clone(root, "https://example.test/orders.git");
        verify(git, never()).fetch(root);
        verify(git).checkoutDetached(root, admittedRevision);
    }

    @Test
    void rejects_the_checkout_when_head_does_not_match_the_admitted_revision() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.currentRevision(root)).thenReturn(RepositoryRevision.ofSha("b".repeat(40)));
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git);

        assertThatThrownBy(() -> checkout.checkout(job(repositoryId, admittedRevision)))
                .hasMessageContaining("checked out revision differs");
    }

    private RepositoryRuntimeRegistry registry(RepositoryId repositoryId) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("repos").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/orders.git");
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), config));
        return new RepositoryRuntimeRegistry(properties);
    }

    private static IndexJob job(RepositoryId repositoryId, RepositoryRevision revision) {
        return new IndexJob(new IndexJobId("job-1"), repositoryId, revision, new GenerationId("generation-1"), 1,
                IndexJobPhase.CHECKOUT, true, Optional.of("worker-1"), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
