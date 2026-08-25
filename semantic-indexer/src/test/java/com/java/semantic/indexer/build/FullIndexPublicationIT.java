package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobWorker;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.ModuleLocator;
import com.java.semantic.indexer.incremental.SourceContractChangeDetector;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

/** Full builds write real batches before validation and publication crosses only the repository pointer boundary. */
@Tag("mongo-it")
class FullIndexPublicationIT {
    private static final String SOURCE_BODY = """
            package orders;
            class Secret { String remote = \"https://user:token@example\"; String token = \"token-123\"; void place() {} }
            """;

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest(name = "{0} leaves the old pointer current")
    @MethodSource("invalidScenarios")
    void invalid_full_build_never_seals_or_publishes(String scenario, Consumer<MongoTemplate> mutation,
                                                     String expectedError) throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            IndexBuildService service = service(template, store, exporter(template, mutation), checkout(scenario, revision()));

            assertThatThrownBy(() -> service.build(job)).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(expectedError)
                    .satisfies(FullIndexPublicationIT::assertSafeMessage);

            assertPreviousPointer(template);
            Document manifest = manifest(template, job.generationId());
            assertThat(manifest.getString("writeState")).isEqualTo(GenerationWriteState.WRITING.name());
            IndexJob failed = store.find(job.id()).orElseThrow();
            assertThat(failed.phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(failed.failureCategory()).contains(IndexFailureCategory.VALIDATION_FAILED);
        }
    }

    @ParameterizedTest(name = "{0} mapped batch leaves the old pointer current")
    @MethodSource("invalidBatchScenarios")
    void invalid_mapped_batch_never_seals_or_publishes(String scenario,
                                                       java.util.function.UnaryOperator<SourceIndexBatch> mutation,
                                                       String expectedError) throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            RepositoryIndexExporter exporter = (repositoryId, requestedRevision, generationId, plan) ->
                    List.of(mutation.apply(validBatch(repositoryId, requestedRevision, generationId)));
            IndexBuildService service = service(template, store, exporter, checkout(scenario, revision()));

            assertThatThrownBy(() -> service.build(job)).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(expectedError)
                    .satisfies(FullIndexPublicationIT::assertSafeMessage);

            assertPreviousPointer(template);
            assertThat(manifest(template, job.generationId()).getString("writeState"))
                    .isEqualTo(GenerationWriteState.WRITING.name());
            assertThat(store.find(job.id()).orElseThrow().failureCategory()).contains(IndexFailureCategory.VALIDATION_FAILED);
        }
    }

    @Test
    void claim_loss_after_planning_prevents_manifest_insertion() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            IndexBuildService service = service(template, store, exporter(template, ignored -> { }),
                    checkout("planner-claim-loss", revision()));
            java.util.concurrent.atomic.AtomicInteger guardChecks = new java.util.concurrent.atomic.AtomicInteger();

            assertThatThrownBy(() -> service.build(job, () -> {
                if (guardChecks.incrementAndGet() == 3) {
                    throw new IllegalStateException("claim lost after planning");
                }
            })).isInstanceOf(IllegalStateException.class).hasMessage("claim lost after planning");

            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                    .countDocuments(new Document("repoId", "orders").append("generationId", job.generationId().value())))
                    .isZero();
            assertPreviousPointer(template);
        }
    }

    @Test
    void changed_checkout_never_seals_or_publishes() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            Path root = checkout("changed-checkout", revision()).root();
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            IndexBuildService service = service(template, store, exporter(template, ignored -> { }), ignored ->
                    new IndexBuildService.CheckedOutRepository(root, calls.getAndIncrement() == 0 ? revision() : revision("b")));

            assertThatThrownBy(() -> service.build(job)).isInstanceOf(RuntimeException.class).hasMessageContaining("CHECKOUT_CHANGED")
                    .satisfies(FullIndexPublicationIT::assertSafeMessage);

            assertPreviousPointer(template);
            assertThat(manifest(template, job.generationId()).getString("writeState")).isEqualTo(GenerationWriteState.WRITING.name());
            assertThat(store.find(job.id()).orElseThrow().failureCategory()).contains(IndexFailureCategory.VALIDATION_FAILED);
        }
    }

    @Test
    void lost_claim_stops_the_writer_before_the_bad_generation_is_published() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            Consumer<MongoTemplate> loseClaim = current -> current.getCollection(IndexCollections.REPOSITORIES).updateOne(
                    new Document("repoId", "orders"), new Document("$unset", new Document("activeJobId", "")
                            .append("activeWorkerId", "").append("activeGenerationId", "").append("claimUntil", "")));
            IndexBuildService service = service(template, store, exporter(template, loseClaim), checkout("lost-claim", revision()));

            assertThatThrownBy(() -> service.build(job)).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("generation lease is not active")
                    .satisfies(FullIndexPublicationIT::assertSafeMessage);

            assertPreviousPointer(template);
            assertThat(manifest(template, job.generationId()).getString("writeState")).isEqualTo(GenerationWriteState.WRITING.name());
        }
    }

    @Test
    void successful_heartbeat_build_maps_a_real_batch_and_uses_the_current_renewed_claim_expiry() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = template(container);
            seedPreviousPointer(template);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            IndexJob job = claimedJob(store);
            Instant initialExpiry = job.claimUntil().orElseThrow();
            RepositoryIndexExporter exporter = (repositoryId, requestedRevision, generationId, plan) -> {
                assertThat(store.renew(job, Duration.ofMinutes(10))).isTrue();
                return List.of(validBatch(repositoryId, requestedRevision, generationId));
            };
            IndexBuildService.CheckedOutRepository checkout = checkout("success", revision());
            IndexBuildService service = service(template, store, exporter, ignored -> checkout);
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                new IndexJobWorker(store, new MongoPublicationWriter(template)).buildWithHeartbeat(job, service,
                        Duration.ofSeconds(10), Duration.ofMinutes(20), scheduler);
            } finally {
                scheduler.shutdownNow();
            }

            Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
            Document manifest = manifest(template, job.generationId());
            Document counts = manifest.get("sealedCollectionCounts", Document.class);
            Date renewedExpiry = Date.from(store.find(job.id()).orElseThrow().claimUntil().orElseThrow());

            assertThat(template.getCollection(IndexCollections.SOURCE_ARTIFACTS).countDocuments(new Document())).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("repoId", "orders")
                    .append("generationId", job.generationId().value()))).isEqualTo(1L);
            assertThat(counts).containsEntry(IndexCollections.SOURCE_ARTIFACTS, 1L).containsEntry(IndexCollections.GENERATION_FILES, 1L)
                    .containsEntry(IndexCollections.SYMBOLS, 1L).containsEntry(IndexCollections.RELATIONS, 1L)
                    .containsEntry(IndexCollections.ENTRY_POINTS, 1L).containsEntry(IndexCollections.SEARCH, 3L);
            assertThat(manifest.getString("validationResult")).isEqualTo("VALID");
            assertThat(manifest.getString("identityDigest")).isEqualTo(repository.getString("manifestDigest"))
                    .isNotEqualTo("0".repeat(64));
            assertThat(manifest.getDate("sealUntil")).isEqualTo(renewedExpiry);
            assertThat(manifest.getDate("sealUntil").toInstant()).isAfter(initialExpiry);
            assertThat(manifest.getString("writeState")).isEqualTo(GenerationWriteState.SEALED_VALID.name());
            assertThat(repository.getString("generationId")).isEqualTo(job.generationId().value());
            assertThat(repository.get("rollbackPointer", Document.class)).containsEntry("generationId", "old-generation")
                    .containsEntry("committedJobId", "old-job");
            assertThat(repository.containsKey("activeJobId")).isFalse();
            assertThat(store.find(job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
        }
    }

    private static Stream<Arguments> invalidScenarios() {
        return Stream.of(
                scenario("missing artifact", template -> template.getCollection(IndexCollections.GENERATION_FILES)
                        .insertOne(missingArtifactFile(writingGeneration(template))), "MISSING_ARTIFACT"),
                scenario("duplicate canonical identity", template -> template.getCollection(IndexCollections.SYMBOLS)
                        .insertOne(projection(writingGeneration(template), "symbol-duplicate", methodCanonical(), validRange())), "DUPLICATE_CANONICAL"),
                scenario("dangling internal relation", template -> template.getCollection(IndexCollections.RELATIONS)
                        .insertOne(relation(writingGeneration(template), "relation-dangling", "internal[14]missing-method")), "DANGLING_INTERNAL_RELATION"),
                scenario("unclassified target", template -> template.getCollection(IndexCollections.RELATIONS)
                        .insertOne(relation(writingGeneration(template), "relation-unclassified", "unknown-target")), "UNCLASSIFIED_RELATION_TARGET"),
                scenario("invalid range", template -> template.getCollection(IndexCollections.SYMBOLS)
                        .insertOne(projection(writingGeneration(template), "symbol-range", "invalid-method", invalidRange())), "INVALID_RANGE"),
                scenario("missing entry-point method", template -> template.getCollection(IndexCollections.ENTRY_POINTS)
                        .insertOne(entryPoint(writingGeneration(template), "entry-missing", "missing-method")), "MISSING_ENTRY_POINT_METHOD"),
                scenario("unsupported schema", template -> template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                        .updateOne(writingManifest(), new Document("$set", new Document("schemaVersion", 99))), "UNSUPPORTED_SCHEMA"),
                scenario("incomplete projection versions", template -> template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                        .updateOne(writingManifest(), new Document("$set", new Document("projectionVersions", List.of(
                                new Document("name", "SOURCES").append("version", 1))))), "INCOMPLETE_PROJECTION_VERSIONS"));
    }

    private static Stream<Arguments> invalidBatchScenarios() {
        return Stream.of(
                Arguments.of("wrong projection revision", (java.util.function.UnaryOperator<SourceIndexBatch>) batch ->
                        validBatch(batch.repositoryId(), revision("b"), batch.generationId()), "PROJECTION_REVISION_MISMATCH"),
                Arguments.of("missing search authority", (java.util.function.UnaryOperator<SourceIndexBatch>) batch ->
                        copyBatch(batch, batch.symbols(), batch.search().subList(0, batch.search().size() - 1)), "INCOMPLETE_SEARCH"),
                Arguments.of("orphan search authority", (java.util.function.UnaryOperator<SourceIndexBatch>) batch -> {
                    java.util.ArrayList<SearchDocument> search = new java.util.ArrayList<>(batch.search());
                    search.add(orphanSearch(batch.repositoryId(), revision(), batch.generationId()));
                    return copyBatch(batch, batch.symbols(), search);
                }, "ORPHAN_SEARCH"),
                Arguments.of("mismatched projection artifact", (java.util.function.UnaryOperator<SourceIndexBatch>) batch -> {
                    SymbolDocument symbol = batch.symbols().getFirst();
                    SymbolDocument mismatched = new SymbolDocument(symbol.repositoryId(), symbol.generationId(), symbol.fact(), symbol.kind(),
                            symbol.owner(), symbol.name(), symbol.signature(), symbol.declaredType(), symbol.modifiers(), symbol.annotations(),
                            new SourceArtifactId("f".repeat(64)), symbol.range());
                    return copyBatch(batch, List.of(mismatched), batch.search());
                }, "PROJECTION_ARTIFACT_MISMATCH"),
                Arguments.of("character crosses source line", (java.util.function.UnaryOperator<SourceIndexBatch>) batch -> {
                    SymbolDocument symbol = batch.symbols().getFirst();
                    SourceRange invalid = new SourceRange(batch.sourcePath(),
                            new SyntaxRange(new SyntaxPosition(0, 200), new SyntaxPosition(0, 201)));
                    SymbolDocument outOfLine = new SymbolDocument(symbol.repositoryId(), symbol.generationId(), symbol.fact(), symbol.kind(),
                            symbol.owner(), symbol.name(), symbol.signature(), symbol.declaredType(), symbol.modifiers(), symbol.annotations(),
                            symbol.sourceArtifactId(), invalid);
                    return copyBatch(batch, List.of(outOfLine), batch.search());
                }, "INVALID_RANGE"));
    }

    private static Arguments scenario(String name, Consumer<MongoTemplate> mutation, String error) {
        return Arguments.of(name, mutation, error);
    }

    private MongoTemplate template(MongoDBContainer container) {
        MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
        new IndexSchemaBootstrap(template).bootstrap();
        return template;
    }

    private IndexBuildService.CheckedOutRepository checkout(String name, RepositoryRevision checkoutRevision) throws java.io.IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(root.resolve("Order.java"), SOURCE_BODY);
        return new IndexBuildService.CheckedOutRepository(root, checkoutRevision);
    }

    private static IndexBuildService service(MongoTemplate template, MongoIndexJobStore store, RepositoryIndexExporter exporter,
                                             IndexBuildService.CheckedOutRepository checkout) {
        return service(template, store, exporter, ignored -> checkout);
    }

    static IndexBuildService service(MongoTemplate template, MongoIndexJobStore store, RepositoryIndexExporter exporter,
                                     IndexBuildService.CheckoutResolver checkoutResolver) {
        return new IndexBuildService(new FullIndexPlanner(), exporter, new MongoGenerationWriter(template),
                new SourceIndexBatchDocumentMapper(template.getConverter()), new GenerationValidator(template),
                new IndexJobWorker(store, new MongoPublicationWriter(template)), checkoutResolver, incrementalBuilder(template));
    }

    private static IncrementalGenerationBuilder incrementalBuilder(MongoTemplate template) {
        ModuleLocator noModuleGraph = new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) {
                return Optional.empty();
            }

            @Override
            public Optional<Set<String>> supportedSources(String module) {
                return Optional.empty();
            }
        };
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner((parent, selected) -> List.of(),
                (change, declarations) -> SourceContractChangeDetector.Impact.bodyOrPrivateChange(), noModuleGraph);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        return new IncrementalGenerationBuilder(template, planner, new ParentGenerationCopier(template, writer));
    }

    private static RepositoryIndexExporter exporter(MongoTemplate template, Consumer<MongoTemplate> mutation) {
        return (repositoryId, requestedRevision, generationId, plan) -> {
            mutation.accept(template);
            return List.of(validBatch(repositoryId, requestedRevision, generationId));
        };
    }

    private static IndexJob claimedJob(MongoIndexJobStore store) {
        IndexJob accepted = store.admit(RepositoryId.of("orders"), revision(), false);
        return store.claim(accepted.id(), "worker-1", Duration.ofMinutes(5)).orElseThrow();
    }

    static SourceIndexBatch validBatch(RepositoryId repositoryId, RepositoryRevision requestedRevision, GenerationId generationId) {
        String sourcePath = "src/Order.java";
        SourceArtifactDocument artifact = SourceArtifactDocument.create(SOURCE_BODY);
        SourceTypeIdentity sourceType = new SourceTypeIdentity(new JavaTypeIdentity("orders", "Secret"), sourcePath);
        MethodTarget method = new MethodTarget(sourceType, "place", List.of());
        CodeFactIdentity methodIdentity = new CodeFactIdentity(repositoryId, requestedRevision, CodeFactKind.METHOD, method);
        CodeFact methodFact = new CodeFact(CodeFactId.from(methodIdentity), methodIdentity);
        SourceRange range = new SourceRange(sourcePath, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));
        SymbolDocument symbol = new SymbolDocument(repositoryId, generationId, methodFact, CodeFactKind.METHOD, "orders.Secret", "place",
                "orders.Secret#place()", new DeclaredType("void"), Set.of(), List.of(), artifact.id(), range);
        RelationTarget target = new RelationTarget.Internal(methodIdentity);
        RelationIdentity relationIdentity = new RelationIdentity(methodIdentity, RelationKind.CALLS, target, range);
        CodeFactIdentity relationIdentityFact = new CodeFactIdentity(repositoryId, requestedRevision, CodeFactKind.TYPE_USAGE, relationIdentity);
        RelationDocument relation = new RelationDocument(repositoryId, generationId,
                new CodeFact(CodeFactId.from(relationIdentityFact), relationIdentityFact), RelationKind.CALLS, methodIdentity, target,
                artifact.id(), range);
        EntryPointTrigger trigger = new EntryPointTrigger(Optional.of("POST"), Optional.of("/orders"), Optional.empty(), Optional.empty());
        EntryPointIdentity entryIdentity = new EntryPointIdentity(EntryPointKind.HTTP, method, trigger);
        CodeFactIdentity entryFactIdentity = new CodeFactIdentity(repositoryId, requestedRevision, CodeFactKind.API_ROUTE, entryIdentity);
        EntryPointDocument entry = new EntryPointDocument(repositoryId, generationId,
                new CodeFact(CodeFactId.from(entryFactIdentity), entryFactIdentity), EntryPointKind.HTTP, method, trigger, range);
        SearchDocument symbolSearch = new SearchDocument(repositoryId, generationId, methodFact.id(), CodeFactKind.METHOD, List.of("orders", "place"),
                Optional.of("orders"), ProjectionName.SYMBOLS, methodIdentity);
        SearchDocument relationSearch = new SearchDocument(repositoryId, generationId, relation.fact().id(), relation.fact().identity().kind(),
                List.of("orders", "calls"), Optional.of("orders"), ProjectionName.RELATIONS, relation.fact().identity());
        SearchDocument entrySearch = new SearchDocument(repositoryId, generationId, entry.fact().id(), entry.fact().identity().kind(),
                List.of("orders", "entry"), Optional.of("orders"), ProjectionName.ENTRY_POINTS, entry.fact().identity());
        return new SourceIndexBatch(repositoryId, generationId, sourcePath, 0, artifact, Optional.empty(), List.of(symbol), List.of(relation), List.of(entry),
                List.of(symbolSearch, relationSearch, entrySearch));
    }

    private static SourceIndexBatch copyBatch(SourceIndexBatch batch, List<SymbolDocument> symbols, List<SearchDocument> search) {
        return new SourceIndexBatch(batch.repositoryId(), batch.generationId(), batch.sourcePath(), batch.sourceChunk(), batch.sourceArtifact(),
                batch.extractionIssue(), symbols, batch.relations(), batch.entryPoints(), search);
    }

    private static SearchDocument orphanSearch(RepositoryId repositoryId, RepositoryRevision requestedRevision, GenerationId generationId) {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(new JavaTypeIdentity("orders", "Secret"), "src/Order.java");
        CodeFactIdentity orphan = new CodeFactIdentity(repositoryId, requestedRevision, CodeFactKind.METHOD,
                new MethodTarget(sourceType, "missing", List.of()));
        return new SearchDocument(repositoryId, generationId, CodeFactId.from(orphan), CodeFactKind.METHOD, List.of("orders", "missing"),
                Optional.of("orders"), ProjectionName.SYMBOLS, orphan);
    }

    private static String writingGeneration(MongoTemplate template) {
        return template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(writingManifest()).first().getString("generationId");
    }

    private static Document writingManifest() {
        return new Document("repoId", "orders").append("writeState", GenerationWriteState.WRITING.name());
    }

    private static Document missingArtifactFile(String generationId) {
        return new Document("repoId", "orders").append("generationId", generationId).append("sourcePath", "src/Missing.java")
                .append("sourceArtifactId", "f".repeat(64)).append("contentHash", "f".repeat(64));
    }

    private static Document projection(String generationId, String symbolId, String canonical, Document range) {
        return new Document("repoId", "orders").append("generationId", generationId).append("symbolId", symbolId).append("canonical", canonical)
                .append("sourcePath", "src/Order.java").append("sourceArtifactId", "a".repeat(64)).append("range", range);
    }

    private static Document relation(String generationId, String relationId, String target) {
        return new Document("repoId", "orders").append("generationId", generationId).append("relationId", relationId)
                .append("from", methodCanonical()).append("target", target).append("sourcePath", "src/Order.java")
                .append("sourceArtifactId", "a".repeat(64)).append("range", validRange());
    }

    private static Document entryPoint(String generationId, String entryPointId, String method) {
        return new Document("repoId", "orders").append("generationId", generationId).append("entryPointId", entryPointId)
                .append("canonical", entryPointId).append("method", method).append("sourcePath", "src/Order.java")
                .append("entryPoint", new Document("range", validRange()));
    }

    private static Document validRange() {
        return new Document("sourceFile", "src/Order.java").append("startLine", 0).append("startCharacter", 0)
                .append("endLine", 0).append("endCharacter", 1);
    }

    private static Document invalidRange() {
        return new Document("sourceFile", "src/Order.java").append("startLine", 9).append("startCharacter", 0)
                .append("endLine", 9).append("endCharacter", 1);
    }

    private static String methodCanonical() {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(new JavaTypeIdentity("orders", "Secret"), "src/Order.java");
        return new CodeFactIdentity(RepositoryId.of("orders"), revision(), CodeFactKind.METHOD,
                new MethodTarget(sourceType, "place", List.of())).canonicalForm();
    }

    private static Document manifest(MongoTemplate template, GenerationId generationId) {
        return template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId", "orders")
                .append("generationId", generationId.value())).first();
    }

    private static void assertPreviousPointer(MongoTemplate template) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
        assertThat(repository.getString("revision")).isEqualTo("b".repeat(40));
        assertThat(repository.getString("generationId")).isEqualTo("old-generation");
        assertThat(repository.getString("manifestDigest")).isEqualTo("c".repeat(64));
        assertThat(repository.getString("committedJobId")).isEqualTo("old-job");
    }

    private static void assertSafeMessage(Throwable exception) {
        assertThat(exception.getMessage()).doesNotContain(SOURCE_BODY, "https://user:token@example", "token-123");
    }

    private static RepositoryRevision revision() {
        return revision("a");
    }

    private static RepositoryRevision revision(String value) {
        return new RepositoryRevision(value.repeat(40));
    }

    private static void seedPreviousPointer(MongoTemplate template) {
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("revision", "b".repeat(40))
                .append("generationId", "old-generation").append("manifestDigest", "c".repeat(64)).append("committedJobId", "old-job")
                .append("publishedAt", Date.from(Instant.parse("2026-08-22T00:00:00Z"))));
    }
}
