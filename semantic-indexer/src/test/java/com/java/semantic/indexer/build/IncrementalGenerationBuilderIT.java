package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.indexer.incremental.IncrementalIndexPlan;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.ModuleLocator;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
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
    void exposes_a_deterministic_full_fallback_when_parent_contract_cannot_be_reused() {
        IncrementalGenerationBuilder.BuildSelection selection = IncrementalGenerationBuilder.BuildSelection.full(
                new IncrementalIndexPlan(true, List.of("src/Current.java"), List.of(), List.of(), List.of("PARENT_CONTRACT_MISMATCH")),
                new FullIndexPlan(java.nio.file.Path.of("."), List.of()));

        assertThat(selection.incremental()).isFalse();
        assertThat(selection.plan().reanalyzePaths()).containsExactly("src/Current.java");
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
                    GenerationValidatorIT.revision());
            validator.recordValid(GenerationValidatorIT.lease(), parentResult);
            writer.mirrorSealUntil(GenerationValidatorIT.lease(), writer.currentClaimUntil(GenerationValidatorIT.lease()));
            writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
            publishParent(template, parentResult.identityDigest().value());
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
            insertChildManifest(template, childLease);
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("revision", "c".repeat(40)).append("generationId", "concurrent-generation")
                            .append("manifestDigest", "d".repeat(64))));
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
                    new ParentGenerationCopier(template, writer)).assemble(job, childLease, selected);

            assertThat(selection.incremental()).isTrue();
            assertThat(selection.exportPaths()).isEmpty();
            assertThat(template.getCollection(IndexCollections.SOURCE_ARTIFACTS).countDocuments()).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(new Document("repoId", "orders")
                    .append("generationId", "g2").append("sourcePath", "src/Order.java"))).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "orders")
                    .append("generationId", "g2"))).isEqualTo(1L);
            assertThat(validator.validate(childLease, new RepositoryRevision("b".repeat(40)), new RepositoryRevision("b".repeat(40))).valid())
                    .isTrue();
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
                    GenerationValidatorIT.revision());
            validator.recordValid(GenerationValidatorIT.lease(), parentResult);
            writer.mirrorSealUntil(GenerationValidatorIT.lease(), writer.currentClaimUntil(GenerationValidatorIT.lease()));
            writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
            publishParent(template, parentResult.identityDigest().value());
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
            insertChildManifest(template, childLease);
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g1"), new Document("$set",
                    new Document("projectionVersions", List.of(new Document("name", "SEARCH").append("version", 1)))));
            Path source = Files.writeString(temporaryDirectory.resolve("Projection.java"), "class Projection { }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    com.java.semantic.model.index.SourceArtifactDocument.create("class Projection { }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, writer)).assemble(childJob(), childLease, selected);

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
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
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
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(true), childLease, selected);

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
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
            insertChildManifest(template, childLease);
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), "class Order { changed(); }");
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    com.java.semantic.model.index.SourceArtifactDocument.create("class Order { changed(); }"))));

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected);

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
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
            insertChildManifest(template, childLease);
            com.java.semantic.model.index.SourceArtifactDocument parentArtifact = FullIndexPublicationIT.validBatch(RepositoryId.of("orders"),
                    GenerationValidatorIT.revision(), GenerationValidatorIT.lease().generationId()).sourceArtifact();
            Path source = Files.writeString(temporaryDirectory.resolve("Order.java"), parentArtifact.utf8Content());
            FullIndexPlan selected = new FullIndexPlan(temporaryDirectory, List.of(new FullIndexPlan.SourceInput("src/Order.java", source,
                    parentArtifact)));
            Files.writeString(source, "class Order { changedAfterPlanning(); }");

            IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder(template, emptyDiffPlanner(),
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected);

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
            MongoGenerationWriter.GenerationLease childLease = childLease(template);
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
                    new ParentGenerationCopier(template, new MongoGenerationWriter(template))).assemble(childJob(), childLease, selected);

            assertThat(selection.plan().copyPaths()).containsExactly("src/Order.java");
            assertThat(selection.plan().reanalyzePaths()).containsExactly("src/Added.java");
            assertThat(selection.exportPaths()).containsExactly("src/Added.java");
        }
    }

    private static void preparePublishedParent(MongoTemplate template) {
        GenerationValidatorIT.seedValidWritingGeneration(template);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        GenerationValidator validator = new GenerationValidator(template);
        GenerationValidator.ValidationResult parentResult = validator.validate(GenerationValidatorIT.lease(), GenerationValidatorIT.revision(),
                GenerationValidatorIT.revision());
        validator.recordValid(GenerationValidatorIT.lease(), parentResult);
        writer.mirrorSealUntil(GenerationValidatorIT.lease(), writer.currentClaimUntil(GenerationValidatorIT.lease()));
        writer.seal(GenerationValidatorIT.lease(), parentResult.identityDigest().value());
        publishParent(template, parentResult.identityDigest().value());
    }

    private static void publishParent(MongoTemplate template, String digest) {
        template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("revision", "a".repeat(40)).append("generationId", "g1").append("manifestDigest", digest)
                        .append("committedJobId", "job-1").append("publishedAt", new Date())).append("$unset",
                new Document("activeJobId", "").append("activeWorkerId", "").append("activeGenerationId", "").append("claimUntil", "")));
        template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", "job-1"), new Document("$set", new Document("active", false)));
    }

    private static MongoGenerationWriter.GenerationLease childLease(MongoTemplate template) {
        Date until = new Date(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
        template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("activeJobId", "job-2").append("activeWorkerId", "worker-2").append("activeGenerationId", "g2")
                        .append("fence", 2L).append("claimUntil", until)));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "job-2").append("repoId", "orders")
                .append("active", true).append("workerId", "worker-2").append("fence", 2L).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of())
                .append("buildParentCaptured", true).append("buildParent", new Document("revision", "a".repeat(40))
                        .append("generationId", "g1").append("manifestDigest", template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                                .find(new Document("generationId", "g1")).first().getString("identityDigest"))
                        .append("committedJobId", "job-1").append("publishedAt", new Date())));
        return new MongoGenerationWriter.GenerationLease(RepositoryId.of("orders"), new GenerationId("g2"), "job-2", "worker-2", 2L);
    }

    private static void insertChildManifest(MongoTemplate template, MongoGenerationWriter.GenerationLease lease) {
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", "orders").append("generationId", "g2")
                .append("sourceRevision", "b".repeat(40)).append("ownerJobId", lease.jobId()).append("ownerWorkerId", lease.workerId())
                .append("fence", lease.fence()).append("writeState", GenerationWriteState.WRITING.name()).append("sealUntil",
                        new Date(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis())).append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
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
                new RepositoryRevision("b".repeat(40)), new GenerationId("g2"), 2L,
                com.java.semantic.indexer.job.IndexJobPhase.CHECKOUT, true, Optional.of("worker-2"),
                Optional.of(new com.java.semantic.model.index.RepositoryFence(2L)), Optional.of(java.time.Instant.now().plusSeconds(300)),
                Optional.empty(), rebuild);
    }
}
