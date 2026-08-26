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
            template.getCollection("index_jobs").insertOne(runningJob());
            template.getCollection("generation_manifests").insertOne(writingManifest());
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationWriteContext lease = context();
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
            template.getCollection("index_jobs").insertOne(runningJob());
            template.getCollection("generation_manifests").insertOne(writingManifest());
            CountDownLatch registrationReached = new CountDownLatch(1);
            CountDownLatch permitRegistration = new CountDownLatch(1);
            MongoGenerationWriter writer = new MongoGenerationWriter(template, () -> {
                registrationReached.countDown();
                await(permitRegistration);
            });
            GenerationWriteContext lease = context();
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

    private static GenerationWriteContext context() {
        return new GenerationWriteContext(new RepositoryId("orders"), new GenerationId("g1"), "job-1");
    }

    private static Document runningJob() {
        return new Document("jobId", "job-1").append("repoId", "orders").append("target", target())
                .append("operation", "BUILD").append("phase", "RUNNING").append("active", true)
                .append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of());
    }

    private static Document target() {
        return new Document("revision", "a".repeat(40)).append("generationId", "g1").append("generation", 1L);
    }

    private static Document writingManifest() {
        return new Document("repoId", "orders").append("generationId", "g1").append("ownerJobId", "job-1")
                .append("identityDigest", "digest").append("writeState", "WRITING")
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of());
    }
}
