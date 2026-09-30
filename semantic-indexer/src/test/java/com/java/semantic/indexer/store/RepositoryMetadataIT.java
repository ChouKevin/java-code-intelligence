package com.java.semantic.indexer.store;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.job.GitEvidenceJobHandler;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobDispatcher;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobProperties;
import com.java.semantic.indexer.job.IndexJobStartupRecovery;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.java.semantic.support.JdtLsTestProperties;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real Git, BSON and dispatcher proof; no JDT is needed for repository metadata. */
@Tag("mongo-it")
class RepositoryMetadataIT {
    private static final RepositoryId REPOSITORY = RepositoryId.of("orders");
    @TempDir Path directory;

    @Test
    void publishes_one_fetch_pair_and_pins_default_and_arbitrary_branch_history_across_remote_movement() throws Exception {
        Path remotePath = directory.resolve("remote.git");
        Path seedPath = directory.resolve("seed");
        Files.createDirectories(directory.resolve("checkouts"));
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
                Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            RepositoryRevision first = commit(seed, seedPath, "first");
            seed.remoteAdd().setName("origin").setUri(new URIish(remotePath.toUri().toString())).call();
            push(seed, "main");
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            seed.checkout().setCreateBranch(true).setName("release/custom").call();
            RepositoryRevision custom = commit(seed, seedPath, "custom");
            push(seed, "release/custom");
            seed.checkout().setName("main").call();
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryProperties properties = properties(remotePath);
            RepositoryRuntimeRegistry repositories = new RepositoryRuntimeRegistry(properties);
            GitRepositoryPort git = spy(new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid()));
            List<RepositoryRevision> moved = new ArrayList<>();
            doAnswer(invocation -> {
                Object captured = invocation.callRealMethod();
                moved.add(commit(seed, seedPath, "moved after fetch"));
                push(seed, "main");
                return captured;
            }).when(git).fetchRemoteBranches(any(Path.class), any(String.class));
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            GitEvidencePublicationStore evidence = new GitEvidencePublicationStore(template);
            RepositoryBuildRunner build = mock(RepositoryBuildRunner.class);
            IndexJobDispatcher dispatcher = dispatcher(jobs, repositories, git, evidence, build);
            IndexRequestService requests = new IndexRequestService(mock(RepositoryRevisionResolver.class), repositories, jobs);
            new ConfiguredRepositoryPublisher(template, repositories).publish();

            IndexJob accepted = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.empty());
            assertThat(accepted.preparation().orElseThrow().branch()).isEmpty();
            dispatcher.dispatchOnce();
            IndexJob complete = jobs.find(accepted.id()).orElseThrow();
            assertThat(complete.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            GitHistoryManifest result = complete.gitEvidence().orElseThrow().metadataResult().orElseThrow();
            assertThat(result.branch()).isEqualTo("main");
            assertThat(result.revision()).isEqualTo(first).isNotEqualTo(moved.getFirst());
            assertThat(result.total()).isEqualTo(1L);
            assertThat(evidence.metadataReady(accepted)).isTrue();
            assertThat(commits(template, result)).extracting(row -> row.getString("revision")).containsExactly(first.value());
            assertThat(template.getCollection(IndexCollections.GIT_BRANCHES).find(new Document("catalogId", result.catalogId().value()))
                    .into(new ArrayList<>())).extracting(row -> row.getString("branch")).containsExactlyInAnyOrder("main", "release/custom");
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .countDocuments(new Document("ownerJobId", accepted.id().value()).append("kind", "HISTORY"))).isEqualTo(1L);
            assertThat(pointer(template)).containsEntry("headRevision", first.value())
                    .containsEntry("observedAt", Date.from(result.preparedAt()));

            IndexJob arbitrary = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.of("release/custom"));
            dispatcher.dispatchOnce();
            GitHistoryManifest arbitraryResult = jobs.find(arbitrary.id()).orElseThrow().gitEvidence().orElseThrow().metadataResult().orElseThrow();
            assertThat(arbitraryResult.branch()).isEqualTo("release/custom");
            assertThat(arbitraryResult.revision()).isEqualTo(custom);
            assertThat(commits(template, arbitraryResult)).extracting(row -> row.getString("revision"))
                    .containsExactly(custom.value(), first.value());
            assertThat(pointer(template)).containsEntry("historyId", arbitraryResult.historyId().value());
            assertThat(template.getCollection(IndexCollections.GIT_BRANCHES).find(
                    new Document("catalogId", arbitraryResult.catalogId().value()).append("branch", "main")).first())
                    .containsEntry("head", moved.getFirst().value());
            Document latestPointer = pointer(template);
            IndexJob unknown = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.of("missing"));
            org.assertj.core.api.Assertions.assertThatThrownBy(dispatcher::dispatchOnce).isInstanceOf(IllegalArgumentException.class);
            assertThat(jobs.find(unknown.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(jobs.find(unknown.id()).orElseThrow().gitEvidence().orElseThrow().metadataResult()).isEmpty();
            assertThat(pointer(template)).isEqualTo(latestPointer);
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .countDocuments(new Document("ownerJobId", unknown.id().value()))).isZero();
            verify(git, times(3)).fetchRemoteBranches(any(Path.class), any(String.class));
            verifyNoInteractions(build);
        }
    }

    @Test
    void interrupted_partial_history_retains_old_pointer_and_restart_only_recognizes_the_exact_complete_pair() throws Exception {
        Path remotePath = directory.resolve("remote.git");
        Path seedPath = directory.resolve("seed");
        Files.createDirectories(directory.resolve("checkouts"));
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
                Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            RepositoryRevision head = commit(seed, seedPath, "first");
            seed.remoteAdd().setName("origin").setUri(new URIish(remotePath.toUri().toString())).call();
            push(seed, "main");
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryProperties properties = properties(remotePath);
            RepositoryRuntimeRegistry repositories = new RepositoryRuntimeRegistry(properties);
            GitRepositoryPort git = spy(new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid()));
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            GitEvidencePublicationStore evidence = new GitEvidencePublicationStore(template);
            IndexRequestService requests = new IndexRequestService(mock(RepositoryRevisionResolver.class), repositories, jobs);
            ConfiguredRepositoryPublisher publisher = new ConfiguredRepositoryPublisher(template, repositories);
            publisher.publish();
            IndexJob accepted = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.empty());
            IndexJob running = jobs.startNextAccepted().orElseThrow();
            new GitEvidenceJobHandler(repositories, git, evidence, mock(RepositoryMutationListener.class)).prepare(running);
            Document published = pointer(template);
            assertThat(evidence.metadataReady(accepted)).isTrue();
            MongoIndexJobStore restarted = new MongoIndexJobStore(template);
            new IndexJobStartupRecovery(restarted, publisher).run(new DefaultApplicationArguments());
            assertThat(restarted.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(pointer(template)).isEqualTo(published);

            doThrow(new IllegalStateException("history extraction interrupted"))
                    .when(git).streamReachableHistory(any(Path.class), eq(head), any());
            IndexJob failed = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.empty());
            IndexJob partial = jobs.startNextAccepted().orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                    mock(RepositoryMutationListener.class)).prepare(partial)).isInstanceOf(IllegalStateException.class);
            assertThat(pointer(template)).isEqualTo(published);
            assertThat(evidence.metadataReady(failed)).isFalse();
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new Document("ownerJobId", failed.id().value()).append("kind", "HISTORY")).first())
                    .containsEntry("state", "FAILED");
            new IndexJobStartupRecovery(new MongoIndexJobStore(template), publisher).run(new DefaultApplicationArguments());
            assertThat(jobs.find(failed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(pointer(template)).isEqualTo(published);
            verify(git, times(2)).fetchRemoteBranches(any(Path.class), any(String.class));
            doCallRealMethod().when(git).streamReachableHistory(any(Path.class), eq(head), any());
            template.executeCommand(new Document("collMod", IndexCollections.REPOSITORIES)
                    .append("validator", new Document("metadataPointer.historyId", published.getString("historyId"))));
            IndexJob unpublished = requests.refreshRepositoryMetadata(REPOSITORY, requestId(), Optional.empty());
            IndexJob unpublishedRunning = jobs.startNextAccepted().orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                    mock(RepositoryMutationListener.class)).prepare(unpublishedRunning)).isInstanceOf(RuntimeException.class);
            assertThat(jobs.find(unpublished.id()).orElseThrow().gitEvidence().orElseThrow().metadataResult()).isPresent();
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new Document("ownerJobId", unpublished.id().value())).into(new ArrayList<>()))
                    .hasSize(2).allSatisfy(manifest -> assertThat(manifest.getString("state")).isEqualTo("READY"));
            assertThat(evidence.metadataReady(unpublished)).isFalse();
            assertThat(pointer(template)).isEqualTo(published);
            new IndexJobStartupRecovery(new MongoIndexJobStore(template), publisher).run(new DefaultApplicationArguments());
            assertThat(jobs.find(unpublished.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(pointer(template)).isEqualTo(published);
            verify(git, times(3)).fetchRemoteBranches(any(Path.class), any(String.class));

            GitHistoryManifest result = jobs.find(accepted.id()).orElseThrow().gitEvidence().orElseThrow().metadataResult().orElseThrow();
            template.getCollection(IndexCollections.GIT_COMMITS).deleteOne(new Document("historyId", result.historyId().value()));
            assertThat(evidence.metadataReady(accepted)).isFalse();
            assertThat(pointer(template)).isEqualTo(published);
        }
    }

    @Test
    void startup_registry_exposes_unindexed_configuration_and_preserves_removed_evidence_without_secrets() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Document oldPointer = new Document("sentinel", "old immutable evidence");
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "removed")
                    .append("configured", true).append("current", oldPointer).append("rollback", oldPointer).append("metadataPointer", oldPointer));
            RepositoryProperties properties = properties(directory.resolve("private-secret-remote.git"));
            properties.getRepositories().get("orders").setDisplayName("Orders");
            properties.getRepositories().get("orders").setProjectGuidePath("docs/codebase/overview.md");
            ConfiguredRepositoryPublisher publisher = new ConfiguredRepositoryPublisher(template, new RepositoryRuntimeRegistry(properties));
            publisher.publish();
            Document configured = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
            assertThat(configured).containsEntry("configured", true).containsEntry("displayName", "Orders")
                    .containsEntry("defaultBranch", "main").containsEntry("projectGuidePath", "docs/codebase/overview.md")
                    .doesNotContainKeys("current", "rollback", "metadataPointer", "url", "remoteUrl", "credentials");
            assertThat(configured.get("configuredAt")).isInstanceOf(Date.class);
            assertThat(configured.toJson()).doesNotContain("private-secret");
            Document removed = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "removed")).first();
            assertThat(removed).containsEntry("configured", false).containsEntry("current", oldPointer)
                    .containsEntry("rollback", oldPointer).containsEntry("metadataPointer", oldPointer);
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("current", oldPointer).append("rollback", oldPointer).append("metadataPointer", oldPointer)));
            properties.getRepositories().get("orders").setProjectGuidePath("");
            new ConfiguredRepositoryPublisher(template, new RepositoryRuntimeRegistry(properties)).publish();
            assertThat(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first())
                    .containsEntry("current", oldPointer).containsEntry("rollback", oldPointer).containsEntry("metadataPointer", oldPointer)
                    .doesNotContainKey("projectGuidePath");
        }
    }

    private IndexJobDispatcher dispatcher(MongoIndexJobStore jobs, RepositoryRuntimeRegistry repositories,
            GitRepositoryPort git, GitEvidencePublicationStore evidence, RepositoryBuildRunner build) {
        GitEvidenceJobHandler handler = new GitEvidenceJobHandler(repositories, git, evidence, mock(RepositoryMutationListener.class));
        return new IndexJobDispatcher(jobs, new IndexJobExecutor(jobs, build, mock(PublicationPort.class),
                Optional.empty(), Optional.of(handler)), new IndexJobProperties(Duration.ofMillis(5)));
    }

    private RepositoryProperties properties(Path remote) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(directory.resolve("checkouts").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl(remote.toUri().toString());
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of("orders", config));
        return properties;
    }

    private static Document pointer(MongoTemplate template) {
        return template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", REPOSITORY.value()))
                .first().get("metadataPointer", Document.class);
    }

    private static List<Document> commits(MongoTemplate template, GitHistoryManifest result) {
        return template.getCollection(IndexCollections.GIT_COMMITS).find(new Document("historyId", result.historyId().value()))
                .sort(new Document("ordinal", 1)).into(new ArrayList<>());
    }

    private static PreparationRequestId requestId() { return new PreparationRequestId(UUID.randomUUID().toString()); }

    private static RepositoryRevision commit(Git seed, Path root, String subject) throws Exception {
        Files.writeString(root.resolve("marker.txt"), subject);
        seed.add().addFilepattern(".").call();
        return RepositoryRevision.ofSha(seed.commit().setMessage(subject).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().name());
    }

    private static void push(Git seed, String branch) throws Exception {
        seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call();
    }
}
