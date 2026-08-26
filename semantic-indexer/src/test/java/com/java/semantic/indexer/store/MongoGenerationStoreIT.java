package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoGenerationStoreIT {
    @ParameterizedTest(name = "{0} rejects intended duplicate")
    @MethodSource("uniqueCollisions")
    void rejects_each_schema_unique_key(String collection, Document first, Document duplicate) {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection(collection).insertOne(first);
            assertThatThrownBy(() -> template.getCollection(collection).insertOne(duplicate)).isInstanceOf(com.mongodb.MongoWriteException.class);
        }
    }

    @Test
    void rejects_a_second_document_with_the_same_immutable_identity_and_different_content() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                    .append("ownerJobId", "job-1").append("writeState", "WRITING"));
            template.getCollection("index_jobs").insertOne(new Document("jobId", "job-1").append("repoId", "orders").append("active", true)
                    .append("target", target("g1")).append("operation", "BUILD").append("phase", "RUNNING")
                    .append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationWriteContext lease = new GenerationWriteContext(new RepositoryId("orders"), new GenerationId("g1"), "job-1");
            MongoGenerationWriter.StoredDocument first = new MongoGenerationWriter.StoredDocument("symbols", new Document("symbolId", "s1").append("name", "first"));
            MongoGenerationWriter.StoredDocument conflicting = new MongoGenerationWriter.StoredDocument("symbols", new Document("symbolId", "s1").append("name", "second"));
            writer.writeBatch(lease, "b1", List.of(first));
            assertThatThrownBy(() -> writer.writeBatch(lease, "b2", List.of(conflicting))).isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getBoolean("active")).isTrue();
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first()
                    .getList("failedOrAmbiguousBatches", String.class)).containsExactly("b2");
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getString("writeState")).isEqualTo("FAILED");
            assertThatThrownBy(() -> writer.writeBatch(lease, "b3", List.of(first))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> writer.seal(lease, "digest")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void reuses_global_source_artifacts_across_generations_without_storing_generation_scope() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            GenerationWriteContext firstLease = writableLease(template, "orders", "g1", "job-1");
            GenerationWriteContext secondLease = writableLease(template, "billing", "g2", "job-2");
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            MongoGenerationWriter.StoredDocument artifact = new MongoGenerationWriter.StoredDocument(IndexCollections.SOURCE_ARTIFACTS,
                    new Document("sourceArtifactId", "artifact-1").append("contentHash", "hash-1").append("utf8Content", "class Example {}"));

            writer.writeBatch(firstLease, "batch-1", List.of(artifact));
            writer.writeBatch(secondLease, "batch-2", List.of(artifact));

            Document stored = template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                    .find(new Document("sourceArtifactId", "artifact-1")).first();
            assertThat(stored).doesNotContainKeys("repoId", "generationId");
            MongoGenerationWriter.StoredDocument conflicting = new MongoGenerationWriter.StoredDocument(IndexCollections.SOURCE_ARTIFACTS,
                    new Document("sourceArtifactId", "artifact-1").append("contentHash", "hash-1").append("utf8Content", "class Changed {}"));
            assertThatThrownBy(() -> writer.writeBatch(secondLease, "batch-3", List.of(conflicting))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void abandons_a_generation_after_process_loss_follows_manifest_batch_registration() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            GenerationWriteContext lease = writableLease(template, "orders", "g1", "job-1");
            MongoGenerationWriter interruptedWriter = new MongoGenerationWriter(template, new MongoGenerationWriter.BatchRegistrationGate() {
                @Override
                public void beforeManifestRegistration() { }

                @Override
                public void afterManifestRegistration() {
                    throw new SimulatedProcessLoss();
                }
            });

            assertThatThrownBy(() -> interruptedWriter.writeBatch(lease, "batch-loss", List.of())).isInstanceOf(SimulatedProcessLoss.class);
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first()
                    .getList("outstandingBatches", String.class)).containsExactly("batch-loss");
            MongoGenerationWriter retryingWriter = new MongoGenerationWriter(template);
            MongoGenerationWriter.StoredDocument attemptedPayload = new MongoGenerationWriter.StoredDocument("symbols",
                    new Document("symbolId", "must-not-write"));

            assertThatThrownBy(() -> retryingWriter.writeBatch(lease, "batch-loss", List.of(attemptedPayload))).isInstanceOf(IllegalStateException.class);

            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first().getBoolean("active")).isTrue();
            assertThat(template.getCollection("index_jobs").find(new Document("jobId", "job-1")).first()
                    .getList("failedOrAmbiguousBatches", String.class)).containsExactly("batch-loss");
            assertThat(template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first().getString("writeState")).isEqualTo("FAILED");
            assertThat(template.getCollection("symbols").countDocuments()).isZero();
            assertThatThrownBy(() -> retryingWriter.seal(lease, "digest")).isInstanceOf(IllegalStateException.class);
        }
    }

    static Stream<Arguments> uniqueCollisions() {
        return Stream.of(
                Arguments.of("repositories", new Document("repoId", "orders"), new Document("repoId", "orders").append("currentGenerationId", "g2")),
                Arguments.of("generation_manifests", generation("g1"), generation("g1").append("digest", "changed")),
                Arguments.of("index_jobs", new Document("jobId", "job-1"), new Document("jobId", "job-1").append("repoId", "other")),
                Arguments.of("index_jobs", new Document("jobId", "a").append("repoId", "orders").append("active", true), new Document("jobId", "b").append("repoId", "orders").append("active", true)),
                Arguments.of("generation_files", scoped("sourcePath", "A.java"), scoped("sourcePath", "A.java").append("sourceArtifactId", "changed")),
                Arguments.of("source_artifacts", new Document("sourceArtifactId", "a").append("contentHash", "one"), new Document("sourceArtifactId", "a").append("contentHash", "two")),
                Arguments.of("source_artifacts", new Document("sourceArtifactId", "a").append("contentHash", "one"), new Document("sourceArtifactId", "b").append("contentHash", "one")),
                Arguments.of("symbols", scoped("symbolId", "s1"), scoped("symbolId", "s1").append("name", "changed")),
                Arguments.of("relations", scoped("relationId", "r1"), scoped("relationId", "r1").append("target", "changed")),
                Arguments.of("entry_points", scoped("entryPointId", "e1"), scoped("entryPointId", "e1").append("path", "/changed")),
                Arguments.of("search", scoped("factId", "f1"), scoped("factId", "f1").append("tokens", List.of("changed"))));
    }

    private static Document generation(String generationId) { return new Document("repoId", "orders").append("generationId", generationId); }
    private static Document scoped(String key, String value) { return new Document("repoId", "orders").append("generationId", "g1").append(key, value); }

    private static GenerationWriteContext writableLease(org.springframework.data.mongodb.core.MongoTemplate template,
                                                         String repositoryId, String generationId, String jobId) {
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repositoryId).append("generationId", generationId)
                .append("ownerJobId", jobId).append("writeState", "WRITING"));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId).append("repoId", repositoryId).append("active", true)
                .append("target", target(generationId)).append("operation", "BUILD").append("phase", "RUNNING")
                .append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
        return new GenerationWriteContext(new RepositoryId(repositoryId), new GenerationId(generationId), jobId);
    }

    private static Document target(String generationId) {
        return new Document("revision", "a".repeat(40)).append("generationId", generationId).append("generation", 1L);
    }

    private static final class SimulatedProcessLoss extends Error { }
}
