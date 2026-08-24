package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
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

    private static Stream<Arguments> invalidGenerationMutations() {
        return Stream.of(
                Arguments.of("missing artifact", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SOURCE_ARTIFACTS).deleteMany(new Document()), "MISSING_ARTIFACT"),
                Arguments.of("duplicate canonical", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).insertOne(symbol("symbol-2", "method-1")), "DUPLICATE_CANONICAL"),
                Arguments.of("dangling internal relation", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.RELATIONS).updateOne(new Document("relationId", "relation-1"),
                                new Document("$set", new Document("target", "internal[14]missing-method"))), "DANGLING_INTERNAL_RELATION"),
                Arguments.of("unclassified unresolved relation", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.RELATIONS).updateOne(new Document("relationId", "relation-1"),
                                new Document("$set", new Document("target", "unknown-target"))), "UNCLASSIFIED_RELATION_TARGET"),
                Arguments.of("invalid range", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document("symbolId", "symbol-1"),
                                new Document("$set", new Document("range", sourceRange(9, 0, 9, 1)))), "INVALID_RANGE"),
                Arguments.of("missing entry method", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.ENTRY_POINTS).updateOne(new Document("entryPointId", "entry-1"),
                                new Document("$set", new Document("method", "missing-method"))), "MISSING_ENTRY_POINT_METHOD"),
                Arguments.of("unsupported schema", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$set", new Document("schemaVersion", 99))), "UNSUPPORTED_SCHEMA"),
                Arguments.of("incomplete projections", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$set", new Document("projectionVersions", List.of(new Document("name", "SOURCES").append("version", 1))))),
                        "INCOMPLETE_PROJECTION_VERSIONS"));
    }

    static void seedValidWritingGeneration(MongoTemplate template) {
        Date until = new Date(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("activeJobId", "job-1")
                .append("activeWorkerId", "worker-1").append("activeGenerationId", "g1").append("fence", 1L).append("claimUntil", until)
                .append("revision", "b".repeat(40)).append("generationId", "old-generation").append("manifestDigest", "c".repeat(64))
                .append("committedJobId", "old-job").append("publishedAt", new Date(0L)));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "job-1").append("repoId", "orders")
                .append("active", true).append("workerId", "worker-1").append("fence", 1L));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("sourceRevision", revision().value()).append("ownerJobId", "job-1").append("ownerWorkerId", "worker-1")
                .append("fence", 1L).append("writeState", "WRITING").append("sealUntil", until).append("writeEpoch", 0L)
                .append("schemaVersion", 1).append("projectionVersions", projectionVersions()).append("identityDigest", "0".repeat(64))
                .append("outstandingBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
        template.getCollection(IndexCollections.SOURCE_ARTIFACTS).insertOne(new Document("sourceArtifactId", "a".repeat(64))
                .append("contentHash", "a".repeat(64)).append("utf8Content", "class Secret { String token = \"token-123\"; }")
                .append("lineOffsets", List.of(0)));
        template.getCollection(IndexCollections.GENERATION_FILES).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("sourcePath", "src/Order.java").append("sourceArtifactId", "a".repeat(64)).append("contentHash", "a".repeat(64)));
        template.getCollection(IndexCollections.SYMBOLS).insertOne(symbol("symbol-1", "method-1"));
        template.getCollection(IndexCollections.RELATIONS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("relationId", "relation-1").append("from", "method-1").append("target", "internal[8]method-1")
                .append("sourceArtifactId", "a".repeat(64)).append("sourcePath", "src/Order.java").append("range", sourceRange(0, 0, 0, 1)));
        template.getCollection(IndexCollections.ENTRY_POINTS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("entryPointId", "entry-1").append("canonical", "entry-1").append("method", "method-1")
                .append("sourcePath", "src/Order.java").append("entryPoint", new Document("range", storedRange(0, 0, 0, 1))));
        template.getCollection(IndexCollections.SEARCH).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("factId", "symbol-1").append("canonical", "method-1"));
    }

    private static Document symbol(String id, String canonical) {
        return new Document("repoId", "orders").append("generationId", "g1").append("symbolId", id).append("canonical", canonical)
                .append("sourceArtifactId", "a".repeat(64)).append("sourcePath", "src/Order.java").append("range", sourceRange(0, 0, 0, 1));
    }

    private static Document sourceRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Document("sourceFile", "src/Order.java").append("range", syntaxRange(startLine, startCharacter, endLine, endCharacter));
    }

    private static Document storedRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Document("sourceFile", "src/Order.java").append("startLine", startLine).append("startCharacter", startCharacter)
                .append("endLine", endLine).append("endCharacter", endCharacter);
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
