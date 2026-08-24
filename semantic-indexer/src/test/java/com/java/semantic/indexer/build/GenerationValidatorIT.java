package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

/** Mongo contract coverage for validation failures that must leave a generation unreachable. */
@Tag("mongo-it")
class GenerationValidatorIT {

    @ParameterizedTest(name = "{0} is rejected without exposing source content")
    @MethodSource("invalidGenerationMutations")
    void rejects_invalid_generation_invariants_without_leaking_source(String scenario,
                                                                      java.util.function.Consumer<MongoTemplate> mutation,
                                                                      String expectedCode) {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            seedValidWritingGeneration(template);
            mutation.accept(template);

            GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease(), revision(), revision());

            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).extracting(GenerationValidationIssue::code).contains(expectedCode);
            assertThat(result.issues().toString()).doesNotContain("class Secret", "https://user:token@", "token-123");
            assertThat(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first()
                    .getString("generationId")).isEqualTo("old-generation");
        }
    }

    @Test
    void rejects_changed_checkout_even_when_staging_documents_are_valid() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            seedValidWritingGeneration(template);

            GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease(), revision(),
                    new RepositoryRevision("b".repeat(40)));

            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).extracting(GenerationValidationIssue::code).contains("CHECKOUT_CHANGED");
        }
    }

    @Test
    void validation_freeze_rejects_a_late_batch_before_it_changes_the_generation() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            seedValidWritingGeneration(template);
            GenerationValidator validator = new GenerationValidator(template);

            GenerationValidator.ValidationResult result = validator.validate(lease(), revision(), revision());
            long searchBefore = template.getCollection(IndexCollections.SEARCH).countDocuments();
            MongoGenerationWriter.StoredDocument lateSearch = new MongoGenerationWriter.StoredDocument(IndexCollections.SEARCH,
                    new Document("repoId", "orders").append("generationId", "g1").append("factId", "late-fact")
                            .append("canonical", "late-canonical"));

            assertThat(result.valid()).isTrue();
            assertThatThrownBy(() -> new MongoGenerationWriter(template).writeBatch(lease(), "late#0", List.of(lateSearch)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("registration failed closed");
            assertThat(template.getCollection(IndexCollections.SEARCH).countDocuments()).isEqualTo(searchBefore);
            validator.recordValid(lease(), result);
            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", "g1")).first()
                    .getString("validationResult")).isEqualTo("VALID");
        }
    }

    private static Stream<Arguments> invalidGenerationMutations() {
        return Stream.of(
                Arguments.of("missing artifact", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SOURCE_ARTIFACTS).deleteMany(new Document()), "MISSING_ARTIFACT"),
                Arguments.of("duplicate canonical", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).insertOne(duplicateSymbol(template)), "DUPLICATE_CANONICAL"),
                Arguments.of("dangling internal relation", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.RELATIONS).updateOne(new Document(),
                                new Document("$set", new Document("target", "internal[14]missing-method"))), "DANGLING_INTERNAL_RELATION"),
                Arguments.of("unclassified unresolved relation", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.RELATIONS).updateOne(new Document(),
                                new Document("$set", new Document("target", "unknown-target"))), "UNCLASSIFIED_RELATION_TARGET"),
                Arguments.of("invalid range", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$set", new Document("range", sourceRange(9, 0, 9, 1)))), "INVALID_RANGE"),
                Arguments.of("negative line character", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$set", new Document("range.range.start.character", -1))), "INVALID_RANGE"),
                Arguments.of("reversed same-line range", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(), new Document("$set",
                                new Document("range.range.start.character", 1).append("range.range.end.character", 0))), "INVALID_RANGE"),
                Arguments.of("missing entry method", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.ENTRY_POINTS).updateOne(new Document(),
                                new Document("$set", new Document("method", "missing-method"))), "MISSING_ENTRY_POINT_METHOD"),
                Arguments.of("mismatched entry route path", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.ENTRY_POINTS).updateOne(new Document(),
                                new Document("$set", new Document("path", "/admin"))), "PROJECTION_IDENTITY_MISMATCH"),
                Arguments.of("unsupported schema", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$set", new Document("schemaVersion", 99))), "UNSUPPORTED_SCHEMA"),
                Arguments.of("incomplete projections", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$set", new Document("projectionVersions", List.of(new Document("name", "SOURCES").append("version", 1))))),
                        "INCOMPLETE_PROJECTION_VERSIONS"),
                Arguments.of("missing search authority", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SEARCH).deleteOne(new Document("authority", "ENTRY_POINTS")), "INCOMPLETE_SEARCH"),
                Arguments.of("orphan search authority", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SEARCH).insertOne(orphanSearch(template)), "ORPHAN_SEARCH"),
                Arguments.of("mismatched search tokens", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SEARCH).updateOne(new Document(),
                                new Document("$set", new Document("tokens", List.of("tampered")))), "SEARCH_AUTHORITY_MISMATCH"),
                Arguments.of("mismatched search package", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SEARCH).updateOne(new Document(),
                                new Document("$set", new Document("package", "tampered"))), "SEARCH_AUTHORITY_MISMATCH"),
                Arguments.of("missing projection source path", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$unset", new Document("sourcePath", ""))), "INVALID_RANGE"),
                Arguments.of("mismatched source artifact", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$set", new Document("sourceArtifactId", new Document("value", "f".repeat(64))))),
                        "PROJECTION_ARTIFACT_MISMATCH"),
                Arguments.of("wrong index definition with correct name", (java.util.function.Consumer<MongoTemplate>) template -> {
                    template.getCollection(IndexCollections.REPOSITORIES).dropIndex("repository_id_unique");
                    template.getCollection(IndexCollections.REPOSITORIES).createIndex(new Document("wrongKey", 1),
                            new IndexOptions().name("repository_id_unique"));
                }, "INVALID_REQUIRED_INDEX"),
                Arguments.of("wrong compound index order with correct name", (java.util.function.Consumer<MongoTemplate>) template -> {
                    template.getCollection(IndexCollections.SYMBOLS).dropIndex("symbol_canonical");
                    template.getCollection(IndexCollections.SYMBOLS).createIndex(
                            new Document("generationId", 1).append("repoId", 1).append("canonical", 1),
                            new IndexOptions().name("symbol_canonical"));
                }, "INVALID_REQUIRED_INDEX"));
    }

    static void seedValidWritingGeneration(MongoTemplate template) {
        Date until = new Date(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("activeJobId", "job-1")
                .append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", until)
                .append("revision", "b".repeat(40)).append("generationId", "old-generation").append("manifestDigest", "c".repeat(64))
                .append("committedJobId", "old-job").append("publishedAt", new Date(0L)));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "job-1").append("repoId", "orders")
                .append("active", true).append("workerId", "worker-1").append("fence", 1L)
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("sourceRevision", revision().value()).append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1")
                .append("fence", 1L).append("writeState", "WRITING").append("sealUntil", until).append("writeEpoch", 0L)
                .append("schemaVersion", 1).append("projectionVersions", projectionVersions()).append("identityDigest", "0".repeat(64))
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        SourceIndexBatch batch = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"), revision(), new GenerationId("g1"));
        new MongoIndexBatchWriter(new MongoGenerationWriter(template), lease(),
                new SourceIndexBatchDocumentMapper(template.getConverter())).write(batch);
    }

    private static Document duplicateSymbol(MongoTemplate template) {
        Document duplicate = new Document(template.getCollection(IndexCollections.SYMBOLS).find().first());
        duplicate.remove("_id");
        duplicate.put("symbolId", "symbol-duplicate");
        return duplicate;
    }

    private static Document orphanSearch(MongoTemplate template) {
        Document orphan = new Document(template.getCollection(IndexCollections.SEARCH).find().first());
        orphan.remove("_id");
        orphan.put("factId", "orphan-fact");
        return orphan;
    }

    private static Document sourceRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Document("sourceFile", "src/Order.java").append("range", syntaxRange(startLine, startCharacter, endLine, endCharacter));
    }

    private static Document syntaxRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Document("start", new Document("line", startLine).append("character", startCharacter))
                .append("end", new Document("line", endLine).append("character", endCharacter));
    }

    static MongoGenerationWriter.GenerationLease lease() {
        return new MongoGenerationWriter.GenerationLease(RepositoryId.of("orders"), new GenerationId("g1"), "job-1", "worker-1", 1L);
    }

    static RepositoryRevision revision() {
        return new RepositoryRevision("a".repeat(40));
    }

    private static List<Document> projectionVersions() {
        return List.of(new Document("name", "SOURCES").append("version", 1), new Document("name", "SYMBOLS").append("version", 1),
                new Document("name", "RELATIONS").append("version", 1), new Document("name", "ENTRY_POINTS").append("version", 1),
                new Document("name", "SEARCH").append("version", 1));
    }
}
