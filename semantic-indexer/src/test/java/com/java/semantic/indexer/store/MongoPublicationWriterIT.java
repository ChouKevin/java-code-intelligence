package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
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
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoPublicationWriterIT {

    @Test
    void publishes_the_first_generation_for_its_running_build_job_and_keeps_the_repository_pointer_only() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrappedTemplate(container);
            insertJob(template, "job-1", "orders", "a", "g1", "BUILD", "RUNNING", true, Optional.empty(), Optional.empty());
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest("orders", "a", "g1", digest("1"), "job-1"));

            PublishedGenerationPointer published = new MongoPublicationWriter(template).publish(command("orders", "a", "g1", "1", "job-1", Optional.empty()));

            assertThat(published.generationId()).isEqualTo(new GenerationId("g1"));
            Document repository = repository(template);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");
            assertThat(repository.containsKey("rollbackPointer")).isFalse();
            assertThat(repository.keySet()).containsOnly("_id", "repoId", "currentPointer");
        }
    }

    @Test
    void rejects_missing_or_nonmatching_build_job_identity_and_another_manifest_owner() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrappedTemplate(container);
            PublishedGenerationPointer parent = pointer("a", "g1", "1", "job-parent", Instant.parse("2026-08-22T00:00:00Z"));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository("orders", parent));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest("orders", "b", "g2", digest("2"), "job-2"));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishGenerationCommand command = command("orders", "b", "g2", "2", "job-2", Optional.of(parent));

            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            insertJob(template, "job-2", "orders", "b", "g2", "BUILD", "ACCEPTED", true, Optional.empty(), Optional.empty());
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("active", false).append("phase", "COMPLETE")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("active", true).append("phase", "RUNNING").append("operation", "ROLLBACK")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("operation", "BUILD").append("repoId", "other")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("repoId", "orders").append("target.generationId", "other-generation")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("target.generationId", "g2")));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"), new Document("$set", new Document("ownerJobId", "other-job")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
        }
    }

    @Test
    void rejects_unsealed_invalid_or_wrong_digest_manifests_without_moving_the_pointer() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrappedTemplate(container);
            PublishedGenerationPointer parent = pointer("a", "g1", "1", "job-parent", Instant.parse("2026-08-22T00:00:00Z"));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository("orders", parent));
            insertJob(template, "job-2", "orders", "b", "g2", "BUILD", "RUNNING", true, Optional.empty(), Optional.empty());
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(sealedManifest("orders", "b", "g2", digest("2"), "job-2"));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishGenerationCommand command = command("orders", "b", "g2", "2", "job-2", Optional.of(parent));

            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"), new Document("$set", new Document("writeState", "WRITING")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"), new Document("$set", new Document("writeState", "SEALED_VALID").append("validationResult", "FAILED")));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"), new Document("$set", new Document("validationResult", "VALID").append("identityDigest", digest("9"))));
            assertRejectedWithoutChangingCurrent(writer, command, template, "g1");
        }
    }

    @Test
    void preserves_expected_parent_cas_and_bounded_rollback_through_a_same_revision_rebuild() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrappedTemplate(container);
            Instant publishedAt = Instant.parse("2026-08-22T00:00:00Z");
            PublishedGenerationPointer r1 = pointer("a", "g1", "1", "job-1", publishedAt);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository("orders", r1));
            insertJob(template, "job-2", "orders", "b", "g2", "BUILD", "RUNNING", true, Optional.empty(), Optional.empty());
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(java.util.List.of(sealedManifest("orders", "b", "g2", digest("2"), "job-2"), sealedManifest("orders", "b", "g3", digest("3"), "job-3")));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);

            assertRejectedWithoutChangingCurrent(writer, command("orders", "b", "g2", "2", "job-2", Optional.of(pointer("c", "g9", "9", "job-9", publishedAt))), template, "g1");
            PublishedGenerationPointer r2 = writer.publish(command("orders", "b", "g2", "2", "job-2", Optional.of(r1)));
            assertThat(repository(template).get("rollbackPointer", Document.class).getString("generationId")).isEqualTo("g1");

            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-2"), new Document("$set", new Document("active", false).append("phase", "COMPLETE")));
            insertJob(template, "job-3", "orders", "b", "g3", "BUILD", "RUNNING", true, Optional.empty(), Optional.empty());
            PublishedGenerationPointer r3 = writer.publish(command("orders", "b", "g3", "3", "job-3", Optional.of(r2)));
            assertThat(r3.revision()).isEqualTo(new RepositoryRevision("b".repeat(40)));
            assertThat(repository(template).get("rollbackPointer", Document.class).getString("generationId")).isEqualTo("g2");
        }
    }

    @Test
    void rolls_back_only_the_exact_bounded_pointer_for_its_running_rollback_job() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrappedTemplate(container);
            Instant r1PublishedAt = Instant.parse("2026-08-22T00:00:00Z");
            Instant r2PublishedAt = Instant.parse("2026-08-22T00:01:00Z");
            PublishedGenerationPointer r1 = pointer("a", "g1", "1", "job-1", r1PublishedAt);
            PublishedGenerationPointer r2 = pointer("b", "g2", "2", "job-2", r2PublishedAt);
            PublishedGenerationPointer arbitrary = pointer("b", "g3", "3", "job-3", r2PublishedAt);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryWithRollback("orders", r2, r1));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(java.util.List.of(sealedManifest("orders", "a", "g1", digest("1"), "job-1"), sealedManifest("orders", "b", "g2", digest("2"), "job-2"), sealedManifest("orders", "b", "g3", digest("3"), "job-3")));
            insertJob(template, "rollback-job", "orders", "a", "g1", "ROLLBACK", "RUNNING", true, Optional.of(r2), Optional.of(r1));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);

            assertThatThrownBy(() -> writer.rollback(new RollbackGenerationCommand(new RepositoryId("orders"), r2, arbitrary, "rollback-job"))).isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g2");
            PublishedGenerationPointer rolledBack = writer.rollback(new RollbackGenerationCommand(new RepositoryId("orders"), r2, r1, "rollback-job"));
            assertThat(rolledBack.generationId()).isEqualTo(new GenerationId("g1"));
            assertThat(repository(template).get("rollbackPointer", Document.class).getString("generationId")).isEqualTo("g2");
        }
    }

    @Test
    void uses_no_transaction_or_replica_set_publication_mechanism() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/java/semantic/indexer/store/MongoPublicationWriter.java"));
        assertThat(source).doesNotContain("MongoTransactionManager", "@Transactional", "ClientSession", "replica" + "Set");
    }

    private static MongoTemplate bootstrappedTemplate(MongoDBContainer container) { MongoTemplate template = MongoSchemaTestSupport.template(container); new IndexSchemaBootstrap(template).bootstrap(); return template; }
    private static void assertRejectedWithoutChangingCurrent(MongoPublicationWriter writer, PublishGenerationCommand command, MongoTemplate template, String expectedGenerationId) { assertThatThrownBy(() -> writer.publish(command)).isInstanceOf(PublicationConflictException.class); assertThat(current(template).getString("generationId")).isEqualTo(expectedGenerationId); }
    private static void insertJob(MongoTemplate template, String jobId, String repositoryId, String revision, String generationId, String operation, String phase, boolean active, Optional<PublishedGenerationPointer> expectedCurrent, Optional<PublishedGenerationPointer> expectedRollback) { Document job = new Document("jobId", jobId).append("repoId", repositoryId).append("active", active).append("phase", phase).append("operation", operation).append("target", new Document("revision", revision.repeat(40)).append("generationId", generationId).append("generation", 1L)); expectedCurrent.ifPresent(pointer -> job.append("expectedCurrent", pointerDocument(pointer))); expectedRollback.ifPresent(pointer -> job.append("expectedRollback", pointerDocument(pointer))); template.getCollection(IndexCollections.INDEX_JOBS).insertOne(job); }
    private static Document repository(String repositoryId, PublishedGenerationPointer current) { return new Document("repoId", repositoryId).append("currentPointer", pointerDocument(current)); }
    private static Document repositoryWithRollback(String repositoryId, PublishedGenerationPointer current, PublishedGenerationPointer rollback) { return repository(repositoryId, current).append("rollbackPointer", pointerDocument(rollback)); }
    private static Document sealedManifest(String repositoryId, String revision, String generationId, String digest, String ownerJobId) { return new Document("repoId", repositoryId).append("sourceRevision", revision.repeat(40)).append("generationId", generationId).append("ownerJobId", ownerJobId).append("writeState", "SEALED_VALID").append("writeEpoch", 1L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions()).append("sealedCollectionCounts", new Document("symbols", 1L)).append("identityDigest", digest).append("validationResult", "VALID").append("validatedAt", new Date()); }
    private static java.util.List<Document> projectionVersions() { return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList(); }
    private static String digest(String digit) { return digit.repeat(64); }
    private static PublishedGenerationPointer pointer(String revision, String generation, String digest, String jobId, Instant publishedAt) { return new PublishedGenerationPointer(new RepositoryRevision(revision.repeat(40)), new GenerationId(generation), new ManifestDigest(digest(digest)), jobId, publishedAt); }
    private static PublishGenerationCommand command(String repositoryId, String revision, String generation, String digest, String jobId, Optional<PublishedGenerationPointer> parent) { return new PublishGenerationCommand(new RepositoryId(repositoryId), new RepositoryRevision(revision.repeat(40)), new GenerationId(generation), jobId, parent, new ManifestDigest(digest(digest))); }
    private static Document pointerDocument(PublishedGenerationPointer pointer) { return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value()).append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId()).append("publishedAt", Date.from(pointer.publishedAt())); }
    private static Document repository(MongoTemplate template) { return Objects.requireNonNull(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first(), "repository should exist"); }
    private static Document current(MongoTemplate template) { return Objects.requireNonNull(repository(template).get("currentPointer", Document.class), "current pointer should exist"); }
}
