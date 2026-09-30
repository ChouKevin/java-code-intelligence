package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.indexer.incremental.IncrementalIndexPlan;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.ModuleLocator;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

/** Contract coverage for incremental assembly; Mongo publication coverage is shared with the full build suite. */
@Tag("mongo-it")
class IncrementalGenerationBuilderIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void copied_mapper_symbols_retain_operations_and_revision_authority_and_reject_missing_payload() throws Exception {
        Path mapperSource = temporaryDirectory.resolve("src/main/resources/mapper/OrderMapper.xml");
        Files.createDirectories(mapperSource.getParent());
        Files.createDirectories(temporaryDirectory.resolve("src/main/java"));
        Files.writeString(mapperSource, """
                <mapper namespace="orders.OrderMapper">
                  <select id="find">select 1</select>
                  <update id="change">update orders set status = 1</update>
                </mapper>
                """);
        FullIndexPlan plan = new FullIndexPlanner().plan(temporaryDirectory);
        List<SourceIndexBatch> batches = new TestSyntaxRepositoryIndexExporter().export(RepositoryId.of("orders"),
                GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId(), plan);
        SourceIndexBatch parentBatch = batches.getFirst();
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            GenerationValidatorIT.seedWritingGeneration(template, parentBatch);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            SourceIndexBatchDocumentMapper mapper = new SourceIndexBatchDocumentMapper(template.getConverter());
            GenerationValidator validator = new GenerationValidator(template);
            GenerationValidator.ValidationResult validated = validator.validate(GenerationValidatorIT.lease(),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.revision(), plan);
            assertThat(validated.valid()).as("valid mapper parent: %s", validated.issues()).isTrue();
            validator.recordValid(GenerationValidatorIT.lease(), validated);
            writer.seal(GenerationValidatorIT.lease(), validated.identityDigest().value());
            publishParent(template, validated.identityDigest().value());
            GenerationWriteContext child = childLease(template);
            insertChildManifest(template, child);
            ParentGenerationCopier copier = new ParentGenerationCopier(template, writer);
            Document parentFilter = new Document("generationId", "g1").append("sourcePath", parentBatch.sourcePath());
            template.getCollection(IndexCollections.SYMBOLS).updateMany(parentFilter,
                    new Document("$unset", new Document("mapperStatementKind", "")));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> copier.copy(child, new GenerationId("g1"),
                    GenerationValidatorIT.revision(), new RepositoryRevision("b".repeat(40)), List.of(parentBatch.sourcePath())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("generationId", "g2"))).isZero();
            for (SymbolDocument symbol : parentBatch.symbols()) {
                template.getCollection(IndexCollections.SYMBOLS).updateOne(new Document("symbolId", symbol.fact().id().value()),
                        new Document("$set", new Document("mapperStatementKind", symbol.mapperStatementKind().orElseThrow().name())));
            }
            RepositoryRevision targetRevision = new RepositoryRevision("b".repeat(40));
            copier.copy(child, new GenerationId("g1"), GenerationValidatorIT.revision(), targetRevision, List.of(parentBatch.sourcePath()));
            List<SymbolDocument> copied = template.getCollection(IndexCollections.SYMBOLS)
                    .find(new Document("generationId", "g2")).map(mapper::reconstructSymbol).into(new java.util.ArrayList<>());
            assertThat(copied).extracting(symbol -> symbol.mapperStatementKind().orElseThrow())
                    .containsExactlyInAnyOrder(MapperStatementKind.SELECT, MapperStatementKind.UPDATE);
            for (SymbolDocument symbol : copied) {
                SymbolDocument parent = parentBatch.symbols().stream().filter(candidate -> candidate.name().equals(symbol.name()))
                        .findFirst().orElseThrow();
                assertThat(symbol.fact().identity().repositoryRevision()).isEqualTo(targetRevision);
                assertThat(symbol.fact().identity().canonicalIdentity()).isEqualTo(parent.fact().identity().canonicalIdentity());
                assertThat(symbol.fact().id()).isEqualTo(CodeFactId.from(symbol.fact().identity())).isNotEqualTo(parent.fact().id());
                assertThat(symbol.sourceArtifactId()).isEqualTo(parent.sourceArtifactId());
                assertThat(symbol.range()).isEqualTo(parent.range());
            }
        }
    }

    @Test
    void rebuilds_selected_sources_when_parent_analysis_fingerprint_differs() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            preparePublishedParent(template);
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(
                    new FullIndexPlan.SourceInput("src/Order.java", source, parentArtifact)));

            AnalysisInputs originalInputs = preparedAnalysis().fingerprint().inputs();
            AnalysisFingerprint changedFingerprint = AnalysisFingerprint.from(new AnalysisInputs(
                    originalInputs.contractVersion(), originalInputs.analyzerDigest(), originalInputs.jdtLsDigest(),
                    originalInputs.launcherJdkDigest(), "f".repeat(64), originalInputs.projects()));
            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template)))
                    .assemble(childJob(), childLease, selected, changedFingerprint);

            assertThat(selection.incremental()).isFalse();
            assertThat(selection.exportPaths()).containsExactly("src/Order.java");
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES)
                    .countDocuments(new Document("generationId", "g2"))).isZero();
            assertThat(template.getCollection(IndexCollections.SYMBOLS)
                    .countDocuments(new Document("generationId", "g2"))).isZero();
        }
    }

    @Test
    void copies_unchanged_parent_records_without_copying_content_addressed_artifacts() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            GenerationValidatorIT.seedValidWritingGeneration(template);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationValidator validator = new GenerationValidator(template);
            GenerationValidator.ValidationResult parentResult = validator.validate(GenerationValidatorIT.lease(), GenerationValidatorIT.revision(),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.expectedPlan());
            validator.recordValid(GenerationValidatorIT.lease(), parentResult);
            writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
            publishParent(template, parentResult.identityDigest().value());
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("currentPointer", new Document("revision", "c".repeat(40)).append("generationId", "concurrent-generation")
                            .append("manifestDigest", "d".repeat(64)))));
            com.java.semantic.model.index.SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    parentArtifact)));
            IncrementalIndexPlanner planner = new IncrementalIndexPlanner((parent, selectedRevision) -> List.of(),
                    (change, declarations) -> com.java.semantic.indexer.incremental.SourceContractChangeDetector.Impact.bodyOrPrivateChange(),
                    modules());
            com.java.semantic.indexer.job.IndexJob job = childJob();

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, planner,
                    new ParentGenerationCopier(template, writer)).assemble(job, childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.incremental()).isTrue();
            assertThat(selection.exportPaths()).isEmpty();
            assertThat(template.getCollection(IndexCollections.SOURCE_ARTIFACTS).countDocuments()).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("repoId", "orders")
                    .append("generationId", "g2").append("sourcePath", "src/Order.java"))).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "orders")
                    .append("generationId", "g2"))).isEqualTo(1L);
            seedChildSource(template, childLease, parentArtifact);
            assertThat(validator.validate(childLease, new RepositoryRevision("b".repeat(40)),
                    new RepositoryRevision("b".repeat(40)), selected).valid()).isTrue();
        }
    }

    @Test
    void rejects_a_parent_projection_mismatch_before_copying_any_parent_records() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            GenerationValidatorIT.seedValidWritingGeneration(template);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationValidator validator = new GenerationValidator(template);
            GenerationValidator.ValidationResult parentResult = validator.validate(GenerationValidatorIT.lease(), GenerationValidatorIT.revision(),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.expectedPlan());
            validator.recordValid(GenerationValidatorIT.lease(), parentResult);
            writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
            publishParent(template, parentResult.identityDigest().value());
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"), new Document("$set",
                    new Document("projectionVersions", List.of(new Document("name", "SEARCH").append("version", 1)))));
            Path source = Files.writeString(temporaryDirectory.resolve("Projection.java"), "class Projection { }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    com.java.semantic.model.index.SourceArtifactDocument.create("class Projection { }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, writer)).assemble(childJob(), childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.incremental()).isFalse();
            assertThat(selection.exportPaths()).containsExactly("src/Order.java");
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("generationId", "g2"))).isZero();
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("generationId", "g2"))).isZero();
        }
    }

    @Test
    void explicit_rebuild_exports_every_selected_source_without_copying_a_compatible_parent() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            preparePublishedParent(template);
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            com.java.semantic.model.index.SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path parentSource = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            Path selectedOnlySource = Files.writeString(temporaryDirectory.resolve("Added.java"), "class Added { }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(
                    new FullIndexPlan.SourceInput("src/Order.java", parentSource, parentArtifact),
                    new FullIndexPlan.SourceInput("src/Added.java", selectedOnlySource,
                            com.java.semantic.model.index.SourceArtifactDocument.create("class Added { }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(true), childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.incremental()).isFalse();
            assertThat(selection.exportPaths()).containsExactly("src/Order.java", "src/Added.java");
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("generationId", "g2"))).isZero();
        }
    }

    @Test
    void stale_parent_artifact_is_reanalyzed_instead_of_copied() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            preparePublishedParent(template);
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), "class Order { changed(); }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    com.java.semantic.model.index.SourceArtifactDocument.create("class Order { changed(); }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.incremental()).isTrue();
            assertThat(selection.plan().copyPaths()).isEmpty();
            assertThat(selection.exportPaths()).containsExactly("src/Order.java");
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("generationId", "g2"))).isZero();
        }
    }

    @Test
    void source_changed_after_planning_is_not_copied_from_the_parent() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            preparePublishedParent(template);
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            com.java.semantic.model.index.SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    parentArtifact)));
            Files.writeString(source, "class Order { changedAfterPlanning(); }");

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.plan().copyPaths()).isEmpty();
            assertThat(selection.exportPaths()).containsExactly("src/Order.java");
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("generationId", "g2"))).isZero();
        }
    }

    @Test
    void selected_source_missing_from_parent_inventory_is_reanalyzed_when_the_diff_is_empty() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            preparePublishedParent(template);
            GenerationWriteContext childLease = childLease(template);
            insertChildManifest(template, childLease);
            com.java.semantic.model.index.SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path parentSource = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            Path selectedOnlySource = Files.writeString(temporaryDirectory.resolve("Added.java"), "class Added { }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(
                    new FullIndexPlan.SourceInput("src/Order.java", parentSource, parentArtifact),
                    new FullIndexPlan.SourceInput("src/Added.java", selectedOnlySource,
                            com.java.semantic.model.index.SourceArtifactDocument.create("class Added { }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected, preparedAnalysis().fingerprint());

            assertThat(selection.plan().copyPaths()).containsExactly("src/Order.java");
            assertThat(selection.plan().reanalyzePaths()).containsExactly("src/Added.java");
            assertThat(selection.exportPaths()).containsExactly("src/Added.java");
        }
    }

    private static TestPreparedAnalysis preparedAnalysis() {
        return TestPreparedAnalysis.forSnapshot(new RepositorySnapshot(
                RepositoryId.of("orders"), Path.of("."), new RepositoryRevision("b".repeat(40))),
                new FullIndexPlan(Path.of("."), List.of()));
    }

    private static void seedChildSource(MongoTemplate template, GenerationWriteContext context,
            SourceArtifactDocument artifact) {
        RepositoryRevision revision = new RepositoryRevision("b".repeat(40));
        SourceEvidencePolicy policy = new SourceEvidencePolicy(1, List.of("src"),
                Set.of("src/Order.java"), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        IndexJob sourceJob = new IndexJob(new IndexJobId(context.jobId()), context.repositoryId(),
                Optional.of(new IndexJobTarget(revision, context.generationId(), 2L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotMembership snapshot = new GitEvidencePublicationStore(template).publishSourceSnapshot(sourceJob,
                revision, List.of(new GitSnapshotEntry("src/Order.java", "100644", "1".repeat(40),
                        GitFileContentStatus.TEXT, artifact.utf8Content().getBytes(StandardCharsets.UTF_8))),
                policy, guide, Instant.now());
        new MongoGenerationWriter(template).recordSourceMembership(context, snapshot, guide, policy,
                new SourceCoverage(1, 0, 0, 0), new SourceStructure(List.of("src"), Map.of(), Map.of()));
    }

    private static void preparePublishedParent(MongoTemplate template) {
        GenerationValidatorIT.seedValidWritingGeneration(template);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        GenerationValidator validator = new GenerationValidator(template);
        GenerationValidator.ValidationResult parentResult = validator.validate(GenerationValidatorIT.lease(), GenerationValidatorIT.revision(),
                GenerationValidatorIT.revision(), GenerationValidatorIT.expectedPlan());
        validator.recordValid(GenerationValidatorIT.lease(), parentResult);
        writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
        publishParent(template, parentResult.identityDigest().value());
    }

    private static void publishParent(MongoTemplate template, String digest) {
        template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("currentPointer", new Document("revision", "a".repeat(40)).append("generationId", "g1")
                        .append("manifestDigest", digest).append("committedJobId", "job-1").append("publishedAt", new Date()))));
        template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-1"), new Document("$set", new Document("active", false)));
    }

    private static GenerationWriteContext childLease(MongoTemplate template) {
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "job-2").append("repoId", "orders")
                .append("target", new Document("revision", "b".repeat(40)).append("generationId", "g2").append("generation", 2L))
                .append("operation", "BUILD").append("phase", "RUNNING").append("active", true).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of())
                .append("expectedParent", new Document("revision", "a".repeat(40))
                        .append("generationId", "g1").append("manifestDigest", template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                                .find(new Document("generationId", "g1")).first().getString("identityDigest"))
                        .append("committedJobId", "job-1").append("publishedAt", new Date())));
        return new GenerationWriteContext(RepositoryId.of("orders"), new GenerationId("g2"), "job-2");
    }

    private static void insertChildManifest(MongoTemplate template, GenerationWriteContext lease) {
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", "orders").append("generationId", "g2")
                .append("sourceRevision", "b".repeat(40)).append("ownerJobId", lease.jobId())
                .append("writeState", GenerationWriteState.WRITING.name()).append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        TestPreparedAnalysis analysis = preparedAnalysis();
        new MongoGenerationWriter(template).recordAnalysis(lease, analysis.fingerprint(), analysis.readinessEvidence());
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }

    private static ModuleLocator modules() {
        return new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) { return Optional.empty(); }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) { return Optional.empty(); }

            @Override
            public Optional<Set<String>> supportedSources(String module) { return Optional.empty(); }
        };
    }

    private static IncrementalIndexPlanner emptyDiffPlanner() {
        return new IncrementalIndexPlanner((parent, selectedRevision) -> List.of(),
                (change, declarations) -> com.java.semantic.indexer.incremental.SourceContractChangeDetector.Impact.bodyOrPrivateChange(), modules());
    }

    private static com.java.semantic.indexer.job.IndexJob childJob() {
        return childJob(false);
    }

    private static com.java.semantic.indexer.job.IndexJob childJob(boolean rebuild) {
        return new com.java.semantic.indexer.job.IndexJob(new com.java.semantic.indexer.job.IndexJobId("job-2"), RepositoryId.of("orders"),
                Optional.of(new com.java.semantic.indexer.job.IndexJobTarget(new RepositoryRevision("b".repeat(40)), new GenerationId("g2"), 2L)),
                com.java.semantic.indexer.job.IndexJobPhase.RUNNING, true, Optional.empty(), rebuild,
                com.java.semantic.indexer.job.IndexJobOperation.BUILD);
    }
}
