package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoGenerationSealIT {
    @Test
    void seals_only_when_batches_are_acknowledged_and_rejects_later_writes() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("activeJobId", "job-1").append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", new java.util.Date(System.currentTimeMillis() + 60_000L)));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true).append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1").append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1").append("fence", 1L).append("identityDigest", "digest").append("writeState", "WRITING").append("sealUntil", new java.util.Date(System.currentTimeMillis() + 60_000L)));
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            MongoGenerationWriter.GenerationLease lease = new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
            writer.writeBatch(lease, "acknowledged", List.of());
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getList("outstandingBatches", String.class)).isEmpty();
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getList("acknowledgedBatches", String.class)).containsExactly("acknowledged");
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getList("outstandingBatches", String.class)).isEmpty();
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getList("acknowledgedBatches", String.class)).containsExactly("acknowledged");
            markValidated(template);
            writer.seal(lease, "digest");
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getString("writeState")).isEqualTo("SEALED_VALID");
            assertThatThrownBy(() -> writer.writeBatch(lease, "late", List.of())).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void does_not_write_a_batch_that_reaches_registration_after_a_stale_seal_check() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            java.util.Date leaseUntil = new java.util.Date(System.currentTimeMillis() + 60_000L);
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("activeJobId", "job-1")
                    .append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", leaseUntil));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true)
                    .append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                    .append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1").append("fence", 1L).append("identityDigest", "digest")
                    .append("writeState", "WRITING").append("sealUntil", leaseUntil));
            CountDownLatch registrationReached = new CountDownLatch(1);
            CountDownLatch permitRegistration = new CountDownLatch(1);
            MongoGenerationWriter writer = new MongoGenerationWriter(template, () -> {
                registrationReached.countDown();
                await(permitRegistration);
            });
            MongoGenerationWriter.GenerationLease lease = new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
            AtomicReference<Throwable> batchFailure = new AtomicReference<>();
            Thread batch = new Thread(() -> {
                try {
                    writer.writeBatch(lease, "batch-1", List.of(new MongoGenerationWriter.StoredDocument("symbols",
                            new Document("symbolId", "s1"))));
                } catch (Throwable throwable) {
                    batchFailure.set(throwable);
                }
            });

            batch.start();
            await(registrationReached);
            markValidated(template);
            writer.seal(lease, "digest");
            permitRegistration.countDown();
            join(batch);

            assertThat(batchFailure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getString("writeState"))
                    .isEqualTo("SEALED_VALID");
            assertThat(template.getCollection("symbols").countDocuments()).isZero();
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getList("outstandingBatches", String.class)).isEmpty();
        }
    }

    @Test
    void refuses_expired_lease_before_any_batch_is_marked_or_written() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("activeJobId", "job-1").append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", new java.util.Date(System.currentTimeMillis() - 1_000L)));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true).append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1").append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1").append("fence", 1L).append("writeState", "WRITING").append("sealUntil", new java.util.Date(System.currentTimeMillis() + 60_000L)));
            MongoGenerationWriter.GenerationLease lease = new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
            assertThatThrownBy(() -> new MongoGenerationWriter(template).writeBatch(lease, "expired", List.of())).isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getList("outstandingBatches", String.class)).isEmpty();
        }
    }

    @Test
    void old_worker_cannot_mirror_or_seal_after_higher_fence_renewal() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            java.util.Date priorExpiry = new java.util.Date(System.currentTimeMillis() + 20_000L);
            java.util.Date replacementExpiry = new java.util.Date(System.currentTimeMillis() + 60_000L);
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("activeJobId", "job-1").append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", replacementExpiry));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true).append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1").append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1").append("fence", 1L).append("identityDigest", "digest").append("writeState", "WRITING").append("sealUntil", priorExpiry));
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            MongoGenerationWriter.GenerationLease oldLease = new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
            writer.mirrorSealUntil(oldLease, replacementExpiry);
            assertThat(template.getCollection("generation_manifests").find(new Document("generationId", "g1")).first().getDate("sealUntil")).isEqualTo(replacementExpiry);
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("activeJobId", "job-2").append("activeWorkerId", "worker-2").append("activeGenerationId", "g2").append("fence", 2L).append("claimUntil", new java.util.Date(System.currentTimeMillis() + 90_000L))));
            assertThatThrownBy(() -> writer.mirrorSealUntil(oldLease, replacementExpiry)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> writer.seal(oldLease, "digest")).isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection("generation_manifests").find(new Document("generationId", "g1")).first().getDate("sealUntil")).isEqualTo(replacementExpiry);
        }
    }

    @Test
    void refuses_to_seal_when_its_mirrored_expiry_has_elapsed_on_the_server() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("activeJobId", "job-1").append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", new java.util.Date(System.currentTimeMillis() + 60_000L)));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true).append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1").append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1").append("fence", 1L).append("identityDigest", "digest").append("writeState", "WRITING").append("sealUntil", new java.util.Date(System.currentTimeMillis() - 1_000L)));
            MongoGenerationWriter.GenerationLease lease = new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
            assertThatThrownBy(() -> new MongoGenerationWriter(template).seal(lease, "digest")).isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getString("writeState")).isEqualTo("WRITING");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for deterministic test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for deterministic test coordination", exception);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            if (thread.isAlive()) {
                throw new AssertionError("batch thread did not finish");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining batch thread", exception);
        }
    }

    private static void markValidated(org.springframework.data.mongodb.core.MongoTemplate template) {
        template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("validationResult", "VALID").append("validatedAt", new java.util.Date())
                        .append("sealedCollectionCounts", new Document("symbols", 0L))));
    }
}
