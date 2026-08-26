package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoIndexJobStoreIT {
    @Test
    void admits_one_active_job_per_repository_and_independent_repositories() {
        try (MongoDBContainer container = container()) {
            MongoIndexJobStore store = store(container);
            RepositoryRevision revision = revision("a");
            IndexJob orders = store.admit(RepositoryId.of("orders"), revision, false);

            assertThatThrownBy(() -> store.admit(RepositoryId.of("orders"), revision, false))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            IndexJob payments = store.admit(RepositoryId.of("payments"), revision, false);

            assertThat(orders.active()).isTrue();
            assertThat(payments.active()).isTrue();
        }
    }

    @Test
    void starts_only_accepted_jobs_and_terminal_transitions_require_running_jobs() {
        try (MongoDBContainer container = container()) {
            MongoIndexJobStore store = store(container);
            IndexJob accepted = store.admit(RepositoryId.of("orders"), revision("a"), false);

            assertThat(store.complete(accepted.id())).isFalse();
            assertThat(store.fail(accepted.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isFalse();
            assertThat(store.startNextAccepted()).hasValueSatisfying(job -> assertThat(job.phase()).isEqualTo(IndexJobPhase.RUNNING));
            assertThat(store.startNextAccepted()).isEmpty();
            assertThat(store.complete(accepted.id())).isTrue();
            assertThat(store.complete(accepted.id())).isFalse();
            assertThat(store.fail(accepted.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isFalse();
        }
    }

    @Test
    void start_next_accepted_claims_the_oldest_job_across_repositories_with_a_job_id_tie_break() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob orders = store.admit(RepositoryId.of("orders"), revision("a"), false);
            IndexJob payments = store.admit(RepositoryId.of("payments"), revision("b"), false);
            Date createdAt = Date.from(Instant.parse("2026-08-26T00:00:00Z"));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", orders.id().value()),
                    new Document("$set", new Document("createdAt", createdAt)));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", payments.id().value()),
                    new Document("$set", new Document("createdAt", createdAt)));
            IndexJob expected = orders.id().value().compareTo(payments.id().value()) < 0 ? orders : payments;
            IndexJob other = expected.equals(orders) ? payments : orders;

            assertThat(store.startNextAccepted()).hasValueSatisfying(job -> {
                assertThat(job.id()).isEqualTo(expected.id());
                assertThat(job.phase()).isEqualTo(IndexJobPhase.RUNNING);
            });
            assertThat(store.find(other.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
        }
    }

    @Test
    void start_next_accepted_claims_one_job_at_most_once_when_two_callers_race() throws Exception {
        try (MongoDBContainer container = container(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            MongoIndexJobStore store = store(container);
            IndexJob accepted = store.admit(RepositoryId.of("orders"), revision("a"), false);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<Optional<IndexJob>> first = callers.submit(() -> claimAfterBarrier(store, ready, release));
            Future<Optional<IndexJob>> second = callers.submit(() -> claimAfterBarrier(store, ready, release));

            assertThat(ready.await(2L, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            List<IndexJob> started = List.of(first.get(2L, TimeUnit.SECONDS), second.get(2L, TimeUnit.SECONDS)).stream()
                    .flatMap(Optional::stream).toList();

            assertThat(started).hasSize(1);
            assertThat(started.getFirst().id()).isEqualTo(accepted.id());
            assertThat(started.getFirst().phase()).isEqualTo(IndexJobPhase.RUNNING);
            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.RUNNING);
        }
    }

    @Test
    void build_target_round_trips_as_a_nested_document() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob admitted = store.admit(RepositoryId.of("orders"), revision("a"), false);

            Document document = template.getCollection(IndexCollections.INDEX_JOBS)
                    .find(new Document("jobId", admitted.id().value())).first();

            assertThat(document.get("target", Document.class)).isNotNull();
            assertThat(document.containsKey("revision")).isFalse();
            assertThat(document.containsKey("generationId")).isFalse();
            assertThat(admitted.target()).contains(new IndexJobTarget(revision("a"), admitted.target().orElseThrow().generationId(), 1L));
            assertThat(store.find(admitted.id()).orElseThrow()).isEqualTo(admitted);
        }
    }

    @Test
    void ensure_returns_inactive_targetless_no_work_only_for_an_exact_current_generation() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            RepositoryRevision revision = revision("f");
            seedPublished(template, "exact", pointer("f", "g-exact", "job-exact"), true, IndexSchemaContract.SCHEMA_VERSION);
            seedPublished(template, "stale", pointer("f", "g-stale", "job-stale"), false, IndexSchemaContract.SCHEMA_VERSION);
            seedPublished(template, "incompatible", pointer("f", "g-incompatible", "job-incompatible"), true, 99);

            IndexJob noWork = store.admitEnsure(RepositoryId.of("exact"), revision);
            IndexJob rebuild = store.admitEnsure(RepositoryId.of("stale"), revision);

            assertThat(noWork.operation()).isEqualTo(IndexJobOperation.NO_WORK);
            assertThat(noWork.active()).isFalse();
            assertThat(noWork.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(noWork.target()).isEmpty();
            assertThat(rebuild.operation()).isEqualTo(IndexJobOperation.BUILD);
            assertThat(rebuild.rebuild()).isTrue();
            assertThatThrownBy(() -> store.admitEnsure(RepositoryId.of("incompatible"), revision))
                    .isInstanceOf(IndexSchemaRebuildRequiredException.class);
        }
    }

    @Test
    void rejects_stale_rollback_admission_and_generates_running_rollback_command() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            PublishedGenerationPointer previous = pointer("a", "g-previous", "job-previous");
            PublishedGenerationPointer current = pointer("b", "g-current", "job-current");
            seedRepositoryWithRollback(template, "orders", current, previous);
            seedManifest(template, "orders", previous, true, IndexSchemaContract.SCHEMA_VERSION);

            assertThatThrownBy(() -> store.admitRollback(RepositoryId.of("orders"), previous, current))
                    .isInstanceOf(PublicationConflictException.class);
            IndexJob accepted = store.admitRollback(RepositoryId.of("orders"), current, previous);
            assertThat(store.rollbackCommand(accepted)).isEmpty();
            IndexJob running = store.startNextAccepted().orElseThrow();

            assertThat(store.rollbackCommand(running)).hasValueSatisfying(command -> {
                assertThat(command.expectedCurrent()).isEqualTo(current);
                assertThat(command.expectedRollback()).isEqualTo(previous);
                assertThat(command.jobId()).isEqualTo(running.id().value());
            });
            new MongoPublicationWriter(template).rollback(store.rollbackCommand(running).orElseThrow());

            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).hasValueSatisfying(job -> assertThat(job.phase()).isEqualTo(IndexJobPhase.COMPLETE));
            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).isEmpty();
        }
    }

    @Test
    void reconciliation_completes_committed_running_jobs_and_fails_only_other_running_jobs() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            store.admit(RepositoryId.of("committed"), revision("c"), false);
            IndexJob committed = store.startNextAccepted().orElseThrow();
            ManifestDigest digest = new ManifestDigest("c".repeat(64));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(committed, digest));
            IndexPublicationIntent intent = store.prepareBuildPublication(committed, digest).orElseThrow();
            new MongoPublicationWriter(template).publish(buildCommand(committed, intent));
            store.admit(RepositoryId.of("uncommitted"), revision("d"), false);
            IndexJob uncommitted = store.startNextAccepted().orElseThrow();
            IndexJob accepted = store.admit(RepositoryId.of("accepted"), revision("e"), false);

            store.reconcileCommittedJobs();
            store.failUnreconciledRunningJobs();

            assertThat(store.find(committed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(store.reconcileCommitted(RepositoryId.of("committed"))).isEmpty();
            assertThat(store.find(uncommitted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(store.find(uncommitted.id()).orElseThrow().failureCategory()).contains(IndexFailureCategory.WORKER_INTERRUPTED);
            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
            assertThat(store.find(accepted.id()).orElseThrow().active()).isTrue();
        }
    }

    @Test
    void build_publication_requires_running_job_and_preserves_rebuild_parent_precondition() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            PublishedGenerationPointer parent = pointer("a", "g-parent", "job-parent");
            seedPublished(template, "orders", parent, true, IndexSchemaContract.SCHEMA_VERSION);
            IndexJob accepted = store.admitRebuild(RepositoryId.of("orders"), revision("b"), parent);
            ManifestDigest digest = new ManifestDigest("b".repeat(64));

            assertThat(store.prepareBuildPublication(accepted, digest)).isEmpty();
            IndexJob running = store.startNextAccepted().orElseThrow();
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(running, digest));
            IndexPublicationIntent intent = store.prepareBuildPublication(running, digest).orElseThrow();
            PublishedGenerationPointer winner = pointer("c", "g-winner", "job-winner");
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("currentPointer", pointerDocument(winner))));

            assertThat(intent.expectedParent()).contains(parent);
            assertThatThrownBy(() -> new MongoPublicationWriter(template).publish(buildCommand(running, intent)))
                    .isInstanceOf(PublicationConflictException.class);
        }
    }

    private static MongoDBContainer container() {
        MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        return container;
    }

    private static MongoIndexJobStore store(MongoDBContainer container) {
        return store(template(container));
    }

    private static MongoIndexJobStore store(MongoTemplate template) {
        new IndexSchemaBootstrap(template).bootstrap();
        return new MongoIndexJobStore(template);
    }

    private static Optional<IndexJob> claimAfterBarrier(MongoIndexJobStore store, CountDownLatch ready, CountDownLatch release)
            throws InterruptedException {
        ready.countDown();
        release.await();
        return store.startNextAccepted();
    }

    private static MongoTemplate template(MongoDBContainer container) {
        return new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
    }

    private static RepositoryRevision revision(String character) {
        return new RepositoryRevision(character.repeat(40));
    }

    private static Document sealedManifest(IndexJob job, ManifestDigest digest) {
        IndexJobTarget target = job.target().orElseThrow();
        return new Document("repoId", job.repositoryId().value()).append("sourceRevision", target.revision().value())
                .append("generationId", target.generationId().value()).append("ownerJobId", job.id().value())
                .append("writeState", "SEALED_VALID").append("writeEpoch", 1L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", projectionVersions()).append("sealedCollectionCounts", new Document("symbols", 1L))
                .append("identityDigest", digest.value()).append("validationResult", "VALID").append("validatedAt", new Date());
    }

    private static PublishedGenerationPointer pointer(String revisionCharacter, String generationId, String committedJobId) {
        return new PublishedGenerationPointer(revision(revisionCharacter), new com.java.semantic.model.index.GenerationId(generationId),
                new ManifestDigest(revisionCharacter.repeat(64)), committedJobId, Instant.parse("2026-08-22T00:00:00Z"));
    }

    private static void seedPublished(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                      boolean requiredProjections, int schemaVersion) {
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryDocument(repositoryId, pointer));
        seedManifest(template, repositoryId, pointer, requiredProjections, schemaVersion);
    }

    private static void seedRepositoryWithRollback(MongoTemplate template, String repositoryId,
                                                   PublishedGenerationPointer current, PublishedGenerationPointer rollback) {
        Document repository = repositoryDocument(repositoryId, current);
        repository.append("rollbackPointer", pointerDocument(rollback));
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository);
    }

    private static void seedManifest(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                     boolean requiredProjections, int schemaVersion) {
        List<Document> projections = requiredProjections ? projectionVersions() : List.of(new Document("name", "SOURCES").append("version", 0));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repositoryId)
                .append("sourceRevision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("ownerJobId", pointer.committedJobId()).append("writeState", "SEALED_VALID").append("writeEpoch", 1L)
                .append("schemaVersion", schemaVersion).append("projectionVersions", projections)
                .append("sealedCollectionCounts", new Document("symbols", 1L)).append("identityDigest", pointer.manifestDigest().value())
                .append("validationResult", "VALID").append("validatedAt", new Date()));
    }

    private static PublishGenerationCommand buildCommand(IndexJob job, IndexPublicationIntent intent) {
        return new PublishGenerationCommand(job.repositoryId(), intent.targetRevision(), intent.targetGenerationId(), job.id().value(),
                intent.expectedParent(), intent.targetManifestDigest());
    }

    private static Document repositoryDocument(String repositoryId, PublishedGenerationPointer pointer) {
        return new Document("repoId", repositoryId).append("currentPointer", pointerDocument(pointer));
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }
}
