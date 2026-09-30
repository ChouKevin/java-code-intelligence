package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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

            GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease(), revision(), revision(),
                    expectedPlan());

            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).extracting(GenerationValidationIssue::code).contains(expectedCode);
            assertThat(result.issues().toString()).doesNotContain("class Secret", "https://user:token@", "token-123");
            assertThat(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first()
                    .get("currentPointer", Document.class).getString("generationId")).isEqualTo("old-generation");
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
                    new RepositoryRevision("b".repeat(40)), expectedPlan());

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

            GenerationValidator.ValidationResult result = validator.validate(lease(), revision(), revision(), expectedPlan());
            long searchBefore = template.getCollection(IndexCollections.SEARCH).countDocuments();
            MongoGenerationWriter.StoredDocument lateSearch = new MongoGenerationWriter.StoredDocument(IndexCollections.SEARCH,
                    new Document("repoId", "orders").append("generationId", "g1").append("factId", "late-fact")
                            .append("canonical", "late-canonical"));

            assertThat(result.valid()).as("validation issues: %s", result.issues()).isTrue();
            assertThatThrownBy(() -> new MongoGenerationWriter(template).writeBatch(lease(), "late#0", List.of(lateSearch)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(template.getCollection(IndexCollections.SEARCH).countDocuments()).isEqualTo(searchBefore);
            validator.recordValid(lease(), result);
            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", "g1")).first()
                    .getString("validationResult")).isEqualTo("VALID");
        }
    }

    @Test
    void rejects_missing_attested_mapper_even_when_persisted_java_projection_is_consistent() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            seedValidWritingGeneration(template);
            FullIndexPlan.SourceInput mapperSource = new FullIndexPlan.SourceInput("src/main/resources/mapper/OrderMapper.xml",
                    Path.of("src/main/resources/mapper/OrderMapper.xml"),
                    SourceArtifactDocument.create(
                            "<mapper namespace=\"orders.OrderMapper\"><select id=\"find\">select 1</select></mapper>"));
            FullIndexPlan plan = new FullIndexPlan(Path.of("."), List.of(expectedPlan().sources().getFirst(), mapperSource));

            GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease(), revision(),
                    revision(), plan);

            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).extracting(GenerationValidationIssue::code).contains("SOURCE_INVENTORY_MISMATCH");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UPDATE", "MISSING", "", "UPSERT"})
    void validates_mapper_operation_before_publication(String malformed,
            @org.junit.jupiter.api.io.TempDir Path repository) throws Exception {
        Path source = repository.resolve("src/main/resources/mapper/OrderMapper.xml");
        Files.createDirectories(source.getParent());
        Files.createDirectories(repository.resolve("src/main/java"));
        Files.writeString(source, "<mapper namespace=\"orders.OrderMapper\"><update id=\"change\">update orders set status = 1</update></mapper>");
        FullIndexPlan plan = new FullIndexPlanner().plan(repository);
        SourceIndexBatch batch = new TestSyntaxRepositoryIndexExporter().export(RepositoryId.of("orders"), revision(),
                lease().generationId(), plan).getFirst();
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            seedWritingGeneration(template, batch);
            Document mutation = "MISSING".equals(malformed)
                    ? new Document("$unset", new Document("mapperStatementKind", ""))
                    : new Document("$set", new Document("mapperStatementKind", malformed));
            assertThat(template.getCollection(IndexCollections.SYMBOLS)
                    .updateMany(new Document("generationId", "g1"), mutation).getMatchedCount()).isEqualTo(1);

            GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease(), revision(), revision(), plan);
            assertThat(result.valid()).isEqualTo("UPDATE".equals(malformed));
            if (!"UPDATE".equals(malformed)) {
                assertThat(result.issues()).extracting(GenerationValidationIssue::code).contains("INVALID_PROJECTION_DOCUMENT");
            }
            assertThat(template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first()
                    .get("currentPointer", Document.class).getString("generationId")).isEqualTo("old-generation");
        }
    }

    private static Stream<Arguments> invalidGenerationMutations() {
        return Stream.of(
                Arguments.of("missing symbol operation", (Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$unset", new Document("mapperStatementKind", ""))), "INVALID_PROJECTION_DOCUMENT"),
                Arguments.of("unknown symbol operation", (Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$set", new Document("mapperStatementKind", "UPSERT"))), "INVALID_PROJECTION_DOCUMENT"),
                Arguments.of("mapper operation on method", (Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document(),
                                new Document("$set", new Document("mapperStatementKind", "UPDATE"))), "INVALID_PROJECTION_DOCUMENT"),
                Arguments.of("missing semantic evidence", (Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$unset", new Document("analysisEvidence", ""))), "MISSING_ANALYSIS_EVIDENCE"),
                Arguments.of("mismatched semantic fingerprint", (Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"),
                                new Document("$set", new Document("analysisFingerprint", "b".repeat(64)))), "ANALYSIS_FINGERPRINT_MISMATCH"),
                Arguments.of("missing artifact", (java.util.function.Consumer<MongoTemplate>) template ->
                        template.getCollection(IndexCollections.SOURCE_ARTIFACTS).deleteMany(new Document()), "MISSING_ARTIFACT"),
                Arguments.of("source artifact bytes changed with retained line layout", (Consumer<MongoTemplate>) template -> {
                    Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS).find().first();
                    String replacement = artifact.getString("utf8Content").replace("Secret", "Forged");
                    template.getCollection(IndexCollections.SOURCE_ARTIFACTS).updateOne(
                            new Document("sourceArtifactId", artifact.getString("sourceArtifactId")),
                            new Document("$set", new Document("utf8Content", replacement)));
                }, "INVALID_SOURCE_ARTIFACT"),
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
        seedWritingGeneration(template, FullIndexPublicationIT.validBatch(RepositoryId.of("orders"), revision(), new GenerationId("g1")));
    }

    static void seedWritingGeneration(MongoTemplate template, SourceIndexBatch batch) {
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("currentPointer",
                new Document("revision", "b".repeat(40)).append("generationId", "old-generation").append("manifestDigest", "c".repeat(64))
                        .append("committedJobId", "old-job").append("publishedAt", new Date(0L))));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "job-1").append("repoId", "orders")
                .append("target", new Document("revision", revision().value()).append("generationId", "g1").append("generation", 1L))
                .append("operation", "BUILD").append("phase", "RUNNING").append("active", true)
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("sourceRevision", revision().value()).append("ownerJobId", "job-1")
                .append("writeState", "WRITING").append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions()).append("identityDigest", "0".repeat(64))
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        TestPreparedAnalysis analysis = TestPreparedAnalysis.forSnapshot(new RepositorySnapshot(
                RepositoryId.of("orders"), Path.of("."), revision()), new FullIndexPlan(Path.of("."), List.of()));
        SemanticAnalysisEvidence evidence = analysis.readinessEvidence();
        List<SemanticAnalysisEvidence.ProjectProof> projects = evidence.projects().stream()
                .map(project -> new SemanticAnalysisEvidence.ProjectProof(project.projectPath(), project.imported(),
                        batch.sourcePath().endsWith(".java") ? project.verifiedSourcePaths() : List.of())).toList();
        new MongoGenerationWriter(template).recordAnalysis(lease(), analysis.fingerprint(),
                new SemanticAnalysisEvidence(evidence.contractVersion(), evidence.fingerprintDigest(), evidence.buildStatus(),
                        projects, evidence.resolution(), evidence.limitations()));
        new MongoIndexBatchWriter(new MongoGenerationWriter(template), lease(),
                new SourceIndexBatchDocumentMapper(template.getConverter())).write(batch);
        SourceEvidencePolicy policy = new SourceEvidencePolicy(1, List.of("src"), Set.of(batch.sourcePath()), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        IndexJob sourceJob = new IndexJob(IndexJobId.create(), RepositoryId.of("orders"),
                Optional.of(new IndexJobTarget(revision(), new GenerationId("g1"), 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotMembership snapshot = new GitEvidencePublicationStore(template).publishSourceSnapshot(sourceJob,
                revision(), List.of(new GitSnapshotEntry(batch.sourcePath(), "100644", "1".repeat(40),
                        GitFileContentStatus.TEXT, batch.sourceArtifact().utf8Content().getBytes(StandardCharsets.UTF_8))),
                policy, guide, Instant.now());
        new MongoGenerationWriter(template).recordSourceMembership(lease(), snapshot, guide, policy,
                new SourceCoverage(1, 0, 0, 0), new SourceStructure(List.of("src"), Map.of(), Map.of()));
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

    static FullIndexPlan expectedPlan() {
        SourceIndexBatch batch = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"), revision(),
                lease().generationId());
        return new FullIndexPlan(Path.of("."), List.of(new FullIndexPlan.SourceInput(batch.sourcePath(),
                Path.of(batch.sourcePath()), batch.sourceArtifact())));
    }

    static GenerationWriteContext lease() {
        return new GenerationWriteContext(RepositoryId.of("orders"), new GenerationId("g1"), "job-1");
    }

    static RepositoryRevision revision() {
        return new RepositoryRevision("a".repeat(40));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue()))
                .toList();
    }
}
