package com.java.semantic.indexer.repository;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PreparationAdmissionIT {
    @Test
    void fixed_branch_admission_pins_fresh_head_and_reuse_never_resolves_or_mutates_the_original(@TempDir Path temporary) throws Exception {
        Path remotePath = temporary.resolve("remote");
        Path managed = Files.createDirectories(temporary.resolve("managed"));
        try (MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4");
                Git remote = Git.init().setInitialBranch("fixed").setDirectory(remotePath.toFile()).call()) {
            mongo.start();
            RepositoryRevision first = commit(remote, remotePath, "first");
            RepositoryProperties properties = new RepositoryProperties();
            properties.setDataRoot(managed.toString());
            RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
            config.setUrl(remotePath.toUri().toString());
            config.setDefaultBranch("fixed");
            properties.setRepositories(Map.of("orders", config));
            RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
            RepositoryId repository = RepositoryId.of("orders");
            JGitRepositoryAdapter git = new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid());
            try (MongoClient client = MongoClients.create(mongo.getConnectionString())) {
                MongoTemplate template = new MongoTemplate(client, "preparation_admission");
                new IndexSchemaBootstrap(template).bootstrap();
                MongoIndexJobStore store = new MongoIndexJobStore(template);
                IndexRequestService service = new IndexRequestService(new GitRevisionResolver(registry, git), registry, store);
                PreparationRequestId id = requestId();
                assertThatThrownBy(() -> service.getJob(repository, Optional.empty(), Optional.of(id)))
                        .isInstanceOf(PreparationRequestNotFoundException.class);
                IndexJob accepted = service.prepareCodebase(repository, id);
                assertThat(accepted.target().orElseThrow().revision()).isEqualTo(first);
                assertThat(accepted.preparationBranch()).contains("fixed");
                assertThat(Files.exists(registry.get(repository).workingTree())).isFalse();
                assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).countDocuments()).isZero();
                assertThatThrownBy(() -> service.refreshRepositoryMetadata(repository, requestId(), Optional.empty()))
                        .isInstanceOf(IndexJobAlreadyActiveException.class);
                RepositoryRevision moved = commit(remote, remotePath, "moved");
                IndexJob running = store.startNextAccepted().orElseThrow();
                ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git, ignored -> { });
                assertThat(checkout.checkout(running).revision()).isEqualTo(first);
                assertThat(git.currentRevision(registry.get(repository).workingTree())).isEqualTo(first);
                assertThat(store.complete(running.id())).isTrue();
                IndexJob fresh = service.prepareCodebase(repository, requestId());
                assertThat(fresh.target().orElseThrow().revision()).isEqualTo(moved);
                Document original = template.getCollection(IndexCollections.INDEX_JOBS)
                        .find(new Document("jobId", accepted.id().value())).first();
                // Remove the remote: any accidental second resolution now fails instead of returning an echo.
                Files.move(remotePath, temporary.resolve("unavailable-remote"));
                assertThatThrownBy(() -> service.prepareCodebase(repository, id))
                        .isInstanceOfSatisfying(PreparationRequestReusedException.class,
                                reused -> assertThat(reused.jobId()).isEqualTo(accepted.id()));
                assertThatThrownBy(() -> service.prepareReview(repository, id, ReviewSelection.commit(moved)))
                        .isInstanceOf(PreparationRequestReusedException.class);
                assertThatThrownBy(() -> service.refreshRepositoryMetadata(repository, id, Optional.of("other")))
                        .isInstanceOf(PreparationRequestReusedException.class);
                assertThat(service.getJob(repository, Optional.empty(), Optional.of(id)).id()).isEqualTo(accepted.id());
                assertThat(service.getJob(repository, Optional.of(accepted.id()), Optional.empty()).id()).isEqualTo(accepted.id());
                assertThatThrownBy(() -> service.getJob(repository, Optional.of(accepted.id()), Optional.of(id)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> service.getJob(repository, Optional.empty(), Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> service.getJob(repository, Optional.of(new IndexJobId("unknown")), Optional.empty()))
                        .isInstanceOf(IndexJobNotFoundException.class);
                assertThat(template.getCollection(IndexCollections.INDEX_JOBS)
                        .find(new Document("jobId", accepted.id().value())).first()).isEqualTo(original);
                store.startNextAccepted().orElseThrow();
                store.fail(fresh.id(), IndexFailureCategory.WORKER_INTERRUPTED);
                ReviewSelection originalSelection = ReviewSelection.range(first, moved);
                IndexJob review = service.prepareReview(repository, requestId(), originalSelection);
                assertThat(review.review().orElseThrow().selection()).isEqualTo(originalSelection);
                assertThat(review.review().orElseThrow().resolvedEndpoints()).isEmpty();
                assertThat(review.preparation().orElseThrow().selection()).contains(originalSelection);
                store.startNextAccepted().orElseThrow();
                store.fail(review.id(), IndexFailureCategory.WORKER_INTERRUPTED);
                IndexJob metadata = service.refreshRepositoryMetadata(repository, requestId(), Optional.empty());
                assertThat(metadata.gitEvidence().orElseThrow().branch()).contains("fixed");
                assertThat(metadata.preparation().orElseThrow().branch()).isEmpty();
                assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).countDocuments()).isZero();
            }
        }
    }

    private static PreparationRequestId requestId() {
        return new PreparationRequestId(UUID.randomUUID().toString());
    }

    private static RepositoryRevision commit(Git git, Path root, String content) throws Exception {
        Files.writeString(root.resolve("Order.java"), "class Order { // " + content + "\n}\n");
        git.add().addFilepattern(".").call();
        return RepositoryRevision.ofSha(git.commit().setMessage(content).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getName());
    }
}
