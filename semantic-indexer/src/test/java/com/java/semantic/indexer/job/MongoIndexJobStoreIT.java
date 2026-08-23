package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoIndexJobStoreIT {
    @Test
    void two_workers_racing_for_one_accepted_job_leave_exactly_one_repository_and_job_claim() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = store.admit(RepositoryId.of("orders"), new RepositoryRevision("c".repeat(40)), false);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<IndexJob>> first = workers.submit(() -> {
                    start.await();
                    return store.claim(job.id(), "worker-a", Duration.ofSeconds(30));
                });
                Future<Optional<IndexJob>> second = workers.submit(() -> {
                    start.await();
                    return store.claim(job.id(), "worker-b", Duration.ofSeconds(30));
                });
                start.countDown();
                List<Optional<IndexJob>> outcomes = List.of(first.get(), second.get());

                assertThat(outcomes.stream().filter(Optional::isPresent)).hasSize(1);
                assertThat(outcomes.stream().filter(Optional::isEmpty)).hasSize(1);
                IndexJob winner = outcomes.stream().flatMap(Optional::stream).findFirst().orElseThrow();
                org.bson.Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                        .find(new org.bson.Document("repoId", "orders")).first();
                assertThat(repository.getString("activeJobId")).isEqualTo(job.id().value());
                assertThat(repository.getString("activeWorkerId")).isEqualTo(winner.workerId().orElseThrow());
                assertThat(store.find(job.id()).orElseThrow().workerId()).contains(winner.workerId().orElseThrow());
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    void losing_job_document_claim_step_compensates_only_the_repository_authority_it_created() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template, (mongo, job, worker, fence, expiry) -> null); // cs-allow
            IndexJob accepted = store.admit(RepositoryId.of("orders"), new RepositoryRevision("d".repeat(40)), false);

            assertThat(store.claim(accepted.id(), "worker-a", Duration.ofSeconds(30))).isEmpty();

            org.bson.Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                    .find(new org.bson.Document("repoId", "orders")).first();
            assertThat(repository.containsKey("activeJobId")).isFalse();
            assertThat(repository.containsKey("activeWorkerId")).isFalse();
            assertThat(repository.containsKey("activeGenerationId")).isFalse();
            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
        }
    }

    @Test
    void build_publication_reconciles_only_when_its_persisted_intent_and_sealed_owner_match() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryId repositoryId = RepositoryId.of("orders");
            IndexJob accepted = store.admit(repositoryId, new RepositoryRevision("e".repeat(40)), false);
            IndexJob claimed = store.claim(accepted.id(), "worker-a", Duration.ofSeconds(60)).orElseThrow();
            ManifestDigest digest = new ManifestDigest("e".repeat(64));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(claimed, digest));

            assertThat(new IndexJobWorker(store, new com.java.semantic.indexer.store.MongoPublicationWriter(template))
                    .publishBuild(claimed, digest)).isTrue();
            assertThat(store.find(claimed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(store.reconcileCommitted(repositoryId)).isEmpty();
        }
    }

    @Test
    void ensure_decision_matrix_returns_no_work_only_for_exact_current_contract() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryRevision revision = new RepositoryRevision("f".repeat(40));
            seedPublished(template, "exact", pointer("f", "g-exact", "job-exact"), true, 1);
            seedPublished(template, "stale", pointer("f", "g-stale", "job-stale"), false, 1);
            seedPublished(template, "incompatible", pointer("f", "g-incompatible", "job-incompatible"), true, 99);
            seedPublished(template, "changed", pointer("f", "g-changed", "job-changed"), true, 1);

            IndexJob noWork = store.admitEnsure(RepositoryId.of("exact"), revision);
            IndexJob stale = store.admitEnsure(RepositoryId.of("stale"), revision);
            assertThat(noWork.operation()).isEqualTo(IndexJobOperation.NO_WORK);
            assertThat(noWork.active()).isFalse();
            assertThat(stale.operation()).isEqualTo(IndexJobOperation.BUILD);
            assertThat(stale.active()).isTrue();
            assertThat(template.getCollection(IndexCollections.INDEX_JOBS).find(new org.bson.Document("jobId", stale.id().value()))
                    .first().getBoolean("rebuild")).isTrue();
            assertThatThrownBy(() -> store.admitEnsure(RepositoryId.of("incompatible"), revision))
                    .isInstanceOf(IndexSchemaRebuildRequiredException.class);
            assertThatThrownBy(() -> store.admitEnsure(RepositoryId.of("incompatible"), new RepositoryRevision("a".repeat(40))))
                    .isInstanceOf(IndexSchemaRebuildRequiredException.class);
            assertThat(store.admitEnsure(RepositoryId.of("missing"), revision).active()).isTrue();
            assertThat(store.admitEnsure(RepositoryId.of("changed"), new RepositoryRevision("a".repeat(40))).active()).isTrue();
        }
    }

    @Test
    void rollback_pointer_swap_survives_a_crash_before_terminal_job_and_reconciles_exactly_once() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            PublishedGenerationPointer previous = pointer("a", "g-previous", "job-previous");
            PublishedGenerationPointer current = pointer("b", "g-current", "job-current");
            seedRepositoryWithRollback(template, "orders", current, previous);
            seedManifest(template, "orders", previous, "worker-previous", 1L, true, 1);
            IndexJob accepted = store.admitRollback(RepositoryId.of("orders"), current, previous);
            IndexJob claimed = store.claim(accepted.id(), "worker-rollback", Duration.ofSeconds(60)).orElseThrow();

            new com.java.semantic.indexer.store.MongoPublicationWriter(template).rollback(store.rollbackCommand(claimed).orElseThrow());

            assertThat(store.find(claimed.id()).orElseThrow().active()).isTrue();
            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).isPresent();
            assertThat(store.find(claimed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).isEmpty();
        }
    }

    @Test
    void revoke_win_blocks_delayed_publish_while_publish_win_reconciles_instead_of_failing() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob revokeAccepted = store.admit(RepositoryId.of("revoke-win"), new RepositoryRevision("a".repeat(40)), false);
            IndexJob revokeClaimed = store.claim(revokeAccepted.id(), "worker-a", Duration.ofSeconds(60)).orElseThrow();
            ManifestDigest revokeDigest = new ManifestDigest("a".repeat(64));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(revokeClaimed, revokeDigest));
            IndexPublicationIntent revokeIntent = store.prepareBuildPublication(revokeClaimed, revokeDigest).orElseThrow();
            assertThat(store.revoke(revokeClaimed)).isTrue();
            assertThatThrownBy(() -> new com.java.semantic.indexer.store.MongoGenerationWriter(template).writeBatch(
                    new com.java.semantic.indexer.store.MongoGenerationWriter.GenerationLease(revokeClaimed.repositoryId(),
                            revokeClaimed.generationId(), revokeClaimed.id().value(), revokeClaimed.workerId().orElseThrow(),
                            revokeClaimed.fence().orElseThrow().value()), "late-write", List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new com.java.semantic.indexer.store.MongoPublicationWriter(template).publish(buildCommand(revokeClaimed, revokeIntent)))
                    .isInstanceOf(com.java.semantic.indexer.store.PublicationConflictException.class);
            assertThat(store.find(revokeClaimed.id()).orElseThrow().active()).isTrue();
            assertThat(store.failAfterRevocation(revokeClaimed)).isTrue();

            IndexJob publishAccepted = store.admit(RepositoryId.of("publish-win"), new RepositoryRevision("b".repeat(40)), false);
            IndexJob publishClaimed = store.claim(publishAccepted.id(), "worker-b", Duration.ofSeconds(60)).orElseThrow();
            ManifestDigest publishDigest = new ManifestDigest("b".repeat(64));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(publishClaimed, publishDigest));
            IndexPublicationIntent publishIntent = store.prepareBuildPublication(publishClaimed, publishDigest).orElseThrow();
            new com.java.semantic.indexer.store.MongoPublicationWriter(template).publish(buildCommand(publishClaimed, publishIntent));
            assertThat(new IndexJobWorker(store, new com.java.semantic.indexer.store.MongoPublicationWriter(template)).failOrCancel(publishClaimed)).isFalse();
            assertThat(store.find(publishClaimed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
        }
    }

    @Test
    void reconciliation_rejects_mismatched_intent_unsealed_manifest_and_wrong_rollback_pointer() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            com.java.semantic.indexer.store.MongoPublicationWriter writer = new com.java.semantic.indexer.store.MongoPublicationWriter(template);

            IndexJob mismatched = claimBuild(store, template, "mismatched-intent", "c");
            IndexPublicationIntent mismatchedIntent = store.prepareBuildPublication(mismatched, new ManifestDigest("c".repeat(64))).orElseThrow();
            writer.publish(buildCommand(mismatched, mismatchedIntent));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new org.bson.Document("jobId", mismatched.id().value()),
                    new org.bson.Document("$set", new org.bson.Document("publicationIntent.targetManifestDigest", "d".repeat(64))));
            assertThat(store.reconcileCommitted(mismatched.repositoryId())).isEmpty();
            assertThat(store.find(mismatched.id()).orElseThrow().active()).isTrue();

            IndexJob unsealed = claimBuild(store, template, "unsealed-manifest", "e");
            IndexPublicationIntent unsealedIntent = store.prepareBuildPublication(unsealed, new ManifestDigest("e".repeat(64))).orElseThrow();
            writer.publish(buildCommand(unsealed, unsealedIntent));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new org.bson.Document("ownerJobId", unsealed.id().value()),
                    new org.bson.Document("$set", new org.bson.Document("writeState", "WRITING")));
            assertThat(store.reconcileCommitted(unsealed.repositoryId())).isEmpty();

            PublishedGenerationPointer previous = pointer("a", "g-reconcile-previous", "job-reconcile-previous");
            PublishedGenerationPointer current = pointer("b", "g-reconcile-current", "job-reconcile-current");
            seedRepositoryWithRollback(template, "wrong-rollback-pointer", current, previous);
            seedManifest(template, "wrong-rollback-pointer", previous, "worker-previous", 1L, true, 1);
            IndexJob rollback = store.claim(store.admitRollback(RepositoryId.of("wrong-rollback-pointer"), current, previous).id(),
                    "worker-rollback", Duration.ofSeconds(60)).orElseThrow();
            writer.rollback(store.rollbackCommand(rollback).orElseThrow());
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new org.bson.Document("repoId", "wrong-rollback-pointer"),
                    new org.bson.Document("$set", new org.bson.Document("rollbackPointer.manifestDigest", "c".repeat(64))));
            assertThat(store.reconcileCommitted(rollback.repositoryId())).isEmpty();
            assertThat(store.find(rollback.id()).orElseThrow().active()).isTrue();
        }
    }

    @Test
    void admits_one_active_job_per_repository_but_allows_independent_repositories_and_allocates_fences() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryRevision revision = new RepositoryRevision("a".repeat(40));
            IndexJob orders = store.admit(RepositoryId.of("orders"), revision, false);

            assertThatThrownBy(() -> store.admit(RepositoryId.of("orders"), revision, false))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            IndexJob payments = store.admit(RepositoryId.of("payments"), revision, false);
            IndexJob claimedOrders = store.claim(orders.id(), "worker-a", Duration.ofSeconds(60)).orElseThrow();
            IndexJob claimedPayments = store.claim(payments.id(), "worker-b", Duration.ofSeconds(60)).orElseThrow();

            assertThat(claimedOrders.fence().orElseThrow().value()).isPositive();
            assertThat(claimedPayments.fence().orElseThrow().value()).isPositive();
        }
    }

    @Test
    void renews_the_exact_repository_claim_then_expires_and_retries_with_a_higher_fence() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryId repositoryId = RepositoryId.of("orders");
            RepositoryRevision revision = new RepositoryRevision("b".repeat(40));
            IndexJob initial = store.admit(repositoryId, revision, false);
            IndexJob claimed = store.claim(initial.id(), "worker-a", Duration.ofSeconds(60)).orElseThrow();

            assertThat(store.renew(claimed, Duration.ofSeconds(120))).isTrue();
            store.failExpiredClaims();
            assertThat(store.find(claimed.id()).orElseThrow().active()).isTrue();

            template.getCollection("repositories").updateOne(new org.bson.Document("repoId", "orders"),
                    new org.bson.Document("$set", new org.bson.Document("claimUntil", java.util.Date.from(java.time.Instant.now().minusSeconds(5)))));
            store.failExpiredClaims();
            assertThat(store.find(claimed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);

            IndexJob retry = store.admit(repositoryId, revision, false);
            IndexJob retriedClaim = store.claim(retry.id(), "worker-b", Duration.ofSeconds(60)).orElseThrow();
            assertThat(retriedClaim.fence().orElseThrow().value()).isGreaterThan(claimed.fence().orElseThrow().value());
        }
    }

    @Test
    void recovery_fails_only_revoked_claims_then_allows_a_new_job() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryRevision revision = new RepositoryRevision("d".repeat(40));

            IndexJob revoked = store.claim(store.admit(RepositoryId.of("revoked"), revision, false).id(), "worker-revoked",
                    Duration.ofSeconds(60)).orElseThrow();
            assertThat(store.revoke(revoked)).isTrue();

            IndexJob unclaimed = store.admit(RepositoryId.of("unclaimed"), revision, false);
            IndexJob current = store.claim(store.admit(RepositoryId.of("current"), revision, false).id(), "worker-current",
                    Duration.ofSeconds(60)).orElseThrow();
            IndexJob committed = claimBuild(store, template, "committed", "e");
            IndexPublicationIntent committedIntent = store.prepareBuildPublication(committed,
                    new ManifestDigest("e".repeat(64))).orElseThrow();
            new com.java.semantic.indexer.store.MongoPublicationWriter(template).publish(buildCommand(committed, committedIntent));

            store.recoverRevokedClaims();
            store.recoverRevokedClaims();

            IndexJob recovered = store.find(revoked.id()).orElseThrow();
            assertThat(recovered.active()).isFalse();
            assertThat(recovered.phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(recovered.failureCategory()).contains(IndexFailureCategory.WORKER_INTERRUPTED);
            assertThat(store.find(unclaimed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
            assertThat(store.find(current.id()).orElseThrow().active()).isTrue();
            assertThat(store.find(committed.id()).orElseThrow().active()).isTrue();
            assertThat(store.admit(RepositoryId.of("revoked"), revision, false).active()).isTrue();
        }
    }

    private static org.bson.Document sealedManifest(IndexJob job, ManifestDigest digest) {
        return new org.bson.Document("repoId", job.repositoryId().value()).append("sourceRevision", job.revision().value())
                .append("generationId", job.generationId().value()).append("ownerJobId", job.id().value())
                .append("ownerWorkerId", job.workerId().orElseThrow()).append("fence", job.fence().orElseThrow().value())
                .append("sealUntil", java.util.Date.from(java.time.Instant.now().plusSeconds(60))).append("writeState", "SEALED_VALID")
                .append("writeEpoch", 1L).append("schemaVersion", 1)
                .append("projectionVersions", List.of(new org.bson.Document("name", "SOURCES").append("version", 1),
                        new org.bson.Document("name", "SYMBOLS").append("version", 1),
                        new org.bson.Document("name", "RELATIONS").append("version", 1),
                        new org.bson.Document("name", "ENTRY_POINTS").append("version", 1),
                        new org.bson.Document("name", "SEARCH").append("version", 1)))
                .append("sealedCollectionCounts", new org.bson.Document("symbols", 1L)).append("identityDigest", digest.value())
                .append("validationResult", "VALID").append("validatedAt", new java.util.Date());
    }

    private static IndexJob claimBuild(MongoIndexJobStore store, MongoTemplate template, String repositoryId,
                                       String revisionCharacter) {
        IndexJob accepted = store.admit(RepositoryId.of(repositoryId), new RepositoryRevision(revisionCharacter.repeat(40)), false);
        IndexJob claimed = store.claim(accepted.id(), "worker-" + revisionCharacter, Duration.ofSeconds(60)).orElseThrow();
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest(claimed,
                new ManifestDigest(revisionCharacter.repeat(64))));
        return claimed;
    }

    private static PublishedGenerationPointer pointer(String revisionCharacter, String generationId, String committedJobId) {
        return new PublishedGenerationPointer(new RepositoryRevision(revisionCharacter.repeat(40)),
                new com.java.semantic.model.index.GenerationId(generationId), new ManifestDigest(revisionCharacter.repeat(64)),
                committedJobId, Instant.parse("2026-08-22T00:00:00Z"));
    }

    private static void seedPublished(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                      boolean requiredProjections, int schemaVersion) {
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryDocument(repositoryId, pointer));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(manifestForPointer(repositoryId, pointer,
                "seed-worker", 1L, requiredProjections, schemaVersion));
    }

    private static void seedRepositoryWithRollback(MongoTemplate template, String repositoryId,
                                                   PublishedGenerationPointer current, PublishedGenerationPointer rollback) {
        org.bson.Document repository = repositoryDocument(repositoryId, current);
        repository.append("rollbackPointer", pointerDocument(rollback));
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository);
    }

    private static void seedManifest(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                     String workerId, long fence, boolean requiredProjections, int schemaVersion) {
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(manifestForPointer(repositoryId, pointer,
                workerId, fence, requiredProjections, schemaVersion));
    }

    private static PublishGenerationCommand buildCommand(IndexJob job, IndexPublicationIntent intent) {
        return new PublishGenerationCommand(job.repositoryId(), intent.targetRevision(), intent.targetGenerationId(), job.id().value(),
                job.workerId().orElseThrow(), job.fence().orElseThrow(), intent.expectedParent(), intent.targetManifestDigest());
    }

    private static org.bson.Document repositoryDocument(String repositoryId, PublishedGenerationPointer pointer) {
        return new org.bson.Document("repoId", repositoryId).append("revision", pointer.revision().value())
                .append("generationId", pointer.generationId().value()).append("manifestDigest", pointer.manifestDigest().value())
                .append("committedJobId", pointer.committedJobId()).append("publishedAt", Date.from(pointer.publishedAt()))
                .append("nextJobGeneration", 0L);
    }

    private static org.bson.Document pointerDocument(PublishedGenerationPointer pointer) {
        return new org.bson.Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static org.bson.Document manifestForPointer(String repositoryId, PublishedGenerationPointer pointer, String workerId,
                                                         long fence, boolean requiredProjections, int schemaVersion) {
        List<org.bson.Document> projections = requiredProjections
                ? List.of(new org.bson.Document("name", "SOURCES").append("version", 1),
                        new org.bson.Document("name", "SYMBOLS").append("version", 1),
                        new org.bson.Document("name", "RELATIONS").append("version", 1),
                        new org.bson.Document("name", "ENTRY_POINTS").append("version", 1),
                        new org.bson.Document("name", "SEARCH").append("version", 1))
                : List.of(new org.bson.Document("name", "SOURCES").append("version", 0));
        return new org.bson.Document("repoId", repositoryId).append("sourceRevision", pointer.revision().value())
                .append("generationId", pointer.generationId().value()).append("ownerJobId", pointer.committedJobId())
                .append("ownerWorkerId", workerId).append("fence", fence).append("sealUntil", Date.from(Instant.now().plusSeconds(60)))
                .append("writeState", "SEALED_VALID").append("writeEpoch", 1L).append("schemaVersion", schemaVersion)
                .append("projectionVersions", projections).append("sealedCollectionCounts", new org.bson.Document("symbols", 1L))
                .append("identityDigest", pointer.manifestDigest().value()).append("validationResult", "VALID")
                .append("validatedAt", new Date());
    }
}
