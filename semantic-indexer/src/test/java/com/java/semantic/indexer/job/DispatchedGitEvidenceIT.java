package com.java.semantic.indexer.job;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.GitEvidenceReadService;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.application.SourceSliceService;
import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** A real JGit/Mongo slice of the normal dispatcher, reusable by later transport journeys. */
@Tag("mongo-it")
class DispatchedGitEvidenceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void dispatches_admitted_catalog_and_history_that_remain_pinned_after_the_remote_moves() throws Exception {
        Path remotePath = temporaryDirectory.resolve("remote.git");
        Path seedPath = temporaryDirectory.resolve("seed");
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            RepositoryRevision first = commit(seed, seedPath, "", "class Evidence { }");
            seed.remoteAdd().setName("origin").setUri(new URIish(remotePath.toUri().toString())).call();
            push(seed, "main");
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            RepositoryRevision head = commit(seed, seedPath, "second", "class Evidence { int version; }");
            push(seed, "main");
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "dispatched_git_evidence");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            RepositoryRuntimeRegistry repositories = registry(remotePath);
            GitEvidenceJobHandler handler = new GitEvidenceJobHandler(repositories, new JGitRepositoryAdapter(properties(remotePath)),
                    new GitEvidencePublicationStore(template));
            IndexJobExecutor executor = new IndexJobExecutor(jobs, mock(RepositoryBuildRunner.class), mock(PublicationPort.class), Optional.empty(),
                    Optional.of(handler));
            IndexJobDispatcher dispatcher = new IndexJobDispatcher(jobs, executor, new IndexJobProperties(Duration.ofMillis(5)));
            IndexRequestService requests = new IndexRequestService(mock(RepositoryRevisionResolver.class), repositories, jobs);

            IndexJob catalogJob = requests.prepareGitRefs(RepositoryId.of("orders"));
            dispatcher.dispatchOnce();
            IndexJob catalogComplete = jobs.find(catalogJob.id()).orElseThrow();
            assertThat(catalogComplete.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            String catalogId = catalogComplete.gitEvidence().orElseThrow().evidenceId().orElseThrow().value();
            GitEvidenceReadService reader = reader(template);
            SemanticQueryContract.GitBranchCollection catalog = reader.branches(
                    new SemanticQueryContract.GitBranchRequest("orders", Optional.of(catalogId), 0, 20));
            assertThat(catalog.items()).extracting(SemanticQueryContract.GitBranchItem::head).containsExactly(head.value());
            httpReader(reader).perform(post("/api/v1/git/branches").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"repositoryId\":\"orders\",\"catalogId\":\"" + catalogId + "\",\"offset\":0,\"limit\":20}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.catalogId").value(catalogId))
                    .andExpect(jsonPath("$.items[0].head").value(head.value()));

            IndexJob historyJob = requests.prepareGitHistory(RepositoryId.of("orders"), catalogId, "main", head.value());
            dispatcher.dispatchOnce();
            IndexJob historyComplete = jobs.find(historyJob.id()).orElseThrow();
            String historyId = historyComplete.gitEvidence().orElseThrow().evidenceId().orElseThrow().value();
            SemanticQueryContract.GitCommitCollection history = reader.commits(
                    new SemanticQueryContract.GitCommitRequest("orders", historyId, head.value(), 0, 20));
            assertThat(history.items()).extracting(SemanticQueryContract.GitCommitItem::revision).containsExactly(head.value(), first.value());
            assertThat(history.items().getFirst().parents()).containsExactly(first.value());
            assertThat(history.items().get(1).subject()).isEmpty();

            commit(seed, seedPath, "third", "class Evidence { int version; int later; }");
            push(seed, "main");
            assertThat(reader.branches(new SemanticQueryContract.GitBranchRequest("orders", Optional.of(catalogId), 0, 20)).items())
                    .extracting(SemanticQueryContract.GitBranchItem::head).containsExactly(head.value());
            assertThat(reader.commits(new SemanticQueryContract.GitCommitRequest("orders", historyId, head.value(), 0, 20)).items())
                    .extracting(SemanticQueryContract.GitCommitItem::revision).containsExactly(head.value(), first.value());
        }
    }

    private RepositoryRuntimeRegistry registry(Path remotePath) {
        return new RepositoryRuntimeRegistry(properties(remotePath));
    }

    private RepositoryProperties properties(Path remotePath) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("checkouts").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl(remotePath.toUri().toString());
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of("orders", config));
        return properties;
    }

    private static GitEvidenceReadService reader(MongoTemplate template) {
        ReadPolicyProperties policy = new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of(), List.of());
        return new GitEvidenceReadService(template, new ConfiguredReadPolicy(policy), Duration.ofSeconds(2));
    }

    private static MockMvc httpReader(GitEvidenceReadService reader) {
        SemanticQueryFacade facade = new SemanticQueryFacade(mock(CurrentRepositoryQueryService.class), mock(CodeFactSearchService.class),
                mock(SourceSliceService.class), mock(CodeFactReadService.class), mock(PublishedDiscoveryQueryService.class),
                mock(PublishedEntryPointQueryService.class), mock(PublishedRelationQueryService.class), reader);
        return standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler()).build();
    }

    private static RepositoryRevision commit(Git seed, Path seedPath, String subject, String source) throws Exception {
        Files.writeString(seedPath.resolve("Evidence.java"), source);
        seed.add().addFilepattern(".").call();
        return RepositoryRevision.ofSha(seed.commit().setMessage(subject).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getId().name());
    }

    private static void push(Git seed, String branch) throws Exception {
        seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call();
    }
}
