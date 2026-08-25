package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RepositoryFence;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoPublicationWriterIT {
    private static final long ACTIVE_UNTIL_MILLIS = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

    @Test
    void publishes_only_a_valid_sealed_generation_and_keeps_one_bounded_rollback_pointer() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Instant r1PublishedAt = Instant.parse("2026-08-22T00:00:00Z");
            PublishedGenerationPointer r1 = pointer("a", "g1", "1", "job-1", r1PublishedAt);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository("orders", r1, "job-2", "worker-2", "g2", 2L));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(java.util.List.of(
                    sealedManifest("orders", "a", "g1", digest("1"), "job-1", "worker-1", 1L),
                    sealedManifest("orders", "b", "g2", digest("2"), "job-2", "worker-2", 2L),
                    sealedManifest("orders", "b", "g3", digest("3"), "job-3", "worker-3", 3L)));

            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishGenerationCommand wrongParent = command("orders", "b", "g2", "2", "job-2", "worker-2", 2L,
                    Optional.of(pointer("c", "g9", "9", "job-9", r1PublishedAt)));
            assertThatThrownBy(() -> writer.publish(wrongParent)).isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");

            PublishGenerationCommand publishG2 = command("orders", "b", "g2", "2", "job-2", "worker-2", 2L, Optional.of(r1));
            PublishedGenerationPointer r2 = writer.publish(publishG2);

            Document repository = current(template);
            assertThat(repository.getString("revision")).isEqualTo("b".repeat(40));
            assertThat(repository.getString("generationId")).isEqualTo("g2");
            assertThat(repository.getString("manifestDigest")).isEqualTo(digest("2"));
            assertThat(repository.containsKey("current")).isFalse();
            Document rollback = repository.get("rollbackPointer", Document.class);
            assertThat(Objects.requireNonNull(rollback, "rollback pointer should exist").getString("generationId")).isEqualTo("g1");
            assertThat(repository.containsKey("activeJobId")).isFalse();

            claim(template, "orders", "job-3", "worker-3", "g3", 3L);
            PublishedGenerationPointer sameRevisionRebuild = writer.publish(command("orders", "b", "g3", "3", "job-3", "worker-3", 3L, Optional.of(r2)));
            assertThat(sameRevisionRebuild.revision()).isEqualTo(new RepositoryRevision("b".repeat(40)));
            assertThat(sameRevisionRebuild.generationId()).isEqualTo(new GenerationId("g3"));
            Document rebuiltRollback = current(template).get("rollbackPointer", Document.class);
            assertThat(Objects.requireNonNull(rebuiltRollback, "rollback pointer should exist").getString("generationId")).isEqualTo("g2");
        }
    }

    @Test
    void rolls_back_only_the_exact_sealed_bounded_pointer() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Instant r1PublishedAt = Instant.parse("2026-08-22T00:00:00Z");
            Instant r2PublishedAt = Instant.parse("2026-08-22T00:01:00Z");
            PublishedGenerationPointer r1 = pointer("a", "g1", "1", "job-1", r1PublishedAt);
            PublishedGenerationPointer r2 = pointer("b", "g2", "2", "job-2", r2PublishedAt);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryWithRollback("orders", r2, r1, "rollback-job", "rollback-worker", "g1", 3L));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(java.util.List.of(
                    sealedManifest("orders", "a", "g1", digest("1"), "job-1", "worker-1", 1L),
                    sealedManifest("orders", "b", "g2", digest("2"), "job-2", "worker-2", 2L),
                    sealedManifest("orders", "b", "g3", digest("3"), "job-3", "worker-3", 3L)));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            RollbackGenerationCommand rollback = new RollbackGenerationCommand(new RepositoryId("orders"), r2, r1,
                    "rollback-job", "rollback-worker", new RepositoryFence(3L));
            RollbackGenerationCommand arbitraryGeneration = new RollbackGenerationCommand(new RepositoryId("orders"), r2,
                    pointer("b", "g3", "3", "job-3", r2PublishedAt), "rollback-job", "rollback-worker", new RepositoryFence(3L));
            assertThatThrownBy(() -> writer.rollback(arbitraryGeneration)).isInstanceOf(PublicationConflictException.class);

            PublishedGenerationPointer rolledBack = writer.rollback(rollback);
            assertThat(rolledBack.generationId()).isEqualTo(new GenerationId("g1"));
            Document rollbackPointer = current(template).get("rollbackPointer", Document.class);
            assertThat(Objects.requireNonNull(rollbackPointer, "rollback pointer should exist").getString("generationId")).isEqualTo("g2");
        }
    }

    @Test
    void publishes_the_first_generation_from_an_exact_repository_claim_without_an_index_job_row() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders")
                    .append("fence", 1L).append("activeJobId", "job-1").append("activeWorkerId", "worker-1")
                    .append("activeGenerationId", "g1").append("claimUntil", activeUntil()));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(
                    sealedManifest("orders", "a", "g1", digest("1"), "job-1", "worker-1", 1L));
            assertThat(template.getCollection(IndexCollections.INDEX_JOBS).countDocuments()).isZero();

            PublishedGenerationPointer published = new MongoPublicationWriter(template).publish(
                    command("orders", "a", "g1", "1", "job-1", "worker-1", 1L, Optional.empty()));

            assertThat(published.generationId()).isEqualTo(new GenerationId("g1"));
            assertThat(current(template).containsKey("rollbackPointer")).isFalse();
        }
    }

    @Test
    void rejects_a_sealed_manifest_whose_expiry_is_stale_after_repository_renewal() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Document manifest = sealedManifest("orders", "a", "g1", digest("1"), "job-1", "worker-1", 1L);
            Date sealedUntil = manifest.getDate("sealUntil");
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders")
                    .append("fence", 1L).append("activeJobId", "job-1").append("activeWorkerId", "worker-1")
                    .append("activeGenerationId", "g1").append("claimUntil", new Date(sealedUntil.getTime() + 60_000L)));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(manifest);

            assertThatThrownBy(() -> new MongoPublicationWriter(template).publish(
                    command("orders", "a", "g1", "1", "job-1", "worker-1", 1L, Optional.empty())))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).containsKey("generationId")).isFalse();
            assertThat(current(template).getString("activeJobId")).isEqualTo("job-1");
        }
    }

    @Test
    void rejects_invalid_manifests_and_a_delayed_owner_after_a_higher_fence_claim() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Instant r1PublishedAt = Instant.parse("2026-08-22T00:00:00Z");
            PublishedGenerationPointer r1 = pointer("a", "g1", "1", "job-1", r1PublishedAt);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository("orders", r1, "job-2", "worker-1", "g2", 2L));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(java.util.List.of(
                    sealedManifest("orders", "a", "g1", digest("1"), "job-1", "worker-1", 1L),
                    withoutValidatedAt(sealedManifest("orders", "b", "g2", digest("2"), "job-2", "worker-1", 2L)),
                    sealedManifest("orders", "b", "g3", digest("3"), "job-3", "worker-2", 3L)));
            job(template, "job-2", "orders", "ACTIVE");
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishGenerationCommand invalidManifest = command("orders", "b", "g2", "2", "job-2", "worker-1", 2L, Optional.of(r1));
            assertThatThrownBy(() -> writer.publish(invalidManifest)).isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");

            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"),
                    new Document("$set", new Document("validatedAt", new Date())));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"),
                    new Document("$set", new Document("validationResult", "FAILED")));
            assertThatThrownBy(() -> writer.publish(invalidManifest)).isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");

            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"),
                    new Document("$set", new Document("validationResult", "VALID")));
            claim(template, "orders", "job-3", "worker-2", "g3", 3L);
            Document activeJob = Objects.requireNonNull(template.getCollection(IndexCollections.INDEX_JOBS)
                    .find(new Document("jobId", "job-2")).first(), "workflow job should exist");
            assertThat(activeJob.getBoolean("active")).isTrue();
            assertThatThrownBy(() -> writer.publish(invalidManifest)).isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");
            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                    .find(new Document("generationId", "g2")).first().getString("writeState")).isEqualTo("SEALED_VALID");
        }
    }

    @Test
    void uses_no_transaction_or_replica_set_publication_mechanism() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/java/semantic/indexer/store/MongoPublicationWriter.java"));
        assertThat(source).doesNotContain("MongoTransactionManager", "@Transactional", "ClientSession", "replicaSet");
    }

    private static Document repository(String repositoryId, PublishedGenerationPointer current, String jobId, String workerId,
                                       String generationId, long fence) {
        return new Document("repoId", repositoryId)
                .append("fence", fence)
                .append("activeJobId", jobId)
                .append("activeWorkerId", workerId)
                .append("activeGenerationId", generationId)
                .append("claimUntil", activeUntil())
                .append("revision", current.revision().value())
                .append("generationId", current.generationId().value())
                .append("manifestDigest", current.manifestDigest().value())
                .append("committedJobId", current.committedJobId())
                .append("publishedAt", Date.from(current.publishedAt()));
    }

    private static Document repositoryWithRollback(String repositoryId, PublishedGenerationPointer current, PublishedGenerationPointer rollback,
                                                   String jobId, String workerId, String generationId, long fence) {
        Document repository = repository(repositoryId, current, jobId, workerId, generationId, fence);
        return repository.append("rollbackPointer", pointerDocument(rollback));
    }

    private static Document sealedManifest(String repositoryId, String revision, String generationId, String digest,
                                           String ownerJobId, String ownerWorkerId, long fence) {
        return new Document("repoId", repositoryId)
                .append("sourceRevision", revision.repeat(40))
                .append("generationId", generationId)
                .append("ownerJobId", ownerJobId)
                .append("ownerWorkerId", ownerWorkerId)
                .append("fence", fence)
                .append("sealUntil", activeUntil())
                .append("writeState", "SEALED_VALID")
                .append("writeEpoch", 1L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", projectionVersions())
                .append("sealedCollectionCounts", new Document("symbols", 1L))
                .append("identityDigest", digest)
                .append("validationResult", "VALID")
                .append("validatedAt", new Date());
    }

    private static java.util.List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue()))
                .toList();
    }

    private static String digest(String digit) {
        return digit.repeat(64);
    }

    private static PublishedGenerationPointer pointer(String revision, String generation, String digest, String jobId, Instant publishedAt) {
        return new PublishedGenerationPointer(new RepositoryRevision(revision.repeat(40)), new GenerationId(generation),
                new ManifestDigest(digest(digest)), jobId, publishedAt);
    }

    private static PublishGenerationCommand command(String repositoryId, String revision, String generation, String digest,
                                                     String jobId, String workerId, long fence,
                                                     Optional<PublishedGenerationPointer> parent) {
        return new PublishGenerationCommand(new RepositoryId(repositoryId), new RepositoryRevision(revision.repeat(40)),
                new GenerationId(generation), jobId, workerId, new RepositoryFence(fence), parent, new ManifestDigest(digest(digest)));
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value())
                .append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value())
                .append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static Document current(MongoTemplate template) {
        return Objects.requireNonNull(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first(),
                "repository should exist");
    }

    private static void claim(MongoTemplate template, String repositoryId, String jobId, String workerId, String generationId, long fence) {
        template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", repositoryId), new Document("$set",
                new Document("activeJobId", jobId).append("activeWorkerId", workerId).append("activeGenerationId", generationId)
                        .append("fence", fence).append("claimUntil", activeUntil())));
    }

    private static Date activeUntil() {
        return new Date(ACTIVE_UNTIL_MILLIS);
    }

    private static void job(MongoTemplate template, String jobId, String repositoryId, String state) {
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId)
                .append("repoId", repositoryId).append("active", "ACTIVE".equals(state)));
    }

    private static Document withoutValidatedAt(Document manifest) {
        Document invalid = new Document(manifest);
        invalid.remove("validatedAt");
        return invalid;
    }
}
