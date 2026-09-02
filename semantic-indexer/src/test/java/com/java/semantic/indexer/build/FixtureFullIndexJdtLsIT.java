package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.adapter.jdtls.DefaultJdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceLifecycleMetrics;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;

/** Exercises a real JDT LS process before exporting fixture source through production projectors. */
@Tag("jdtls-it")
class FixtureFullIndexJdtLsIT {

    private static final Path VIDEO_FIXTURE = Path.of("fixtures/uat/video-service");
    private static final Path PAYMENT_FIXTURE = Path.of("fixtures/uat/payment-service");
    private static final Path ORDER_FIXTURE = Path.of("fixtures/uat/order-service");

    @TempDir
    Path temporaryDirectory;

    @Test
    void applies_the_reviewed_payment_v2_patch_once_to_the_semantic_owned_v1_fixture() throws IOException, InterruptedException {
        Path paymentRoot = copyFixture(PAYMENT_FIXTURE, temporaryDirectory.resolve("payment-v1"));
        Path patch = Path.of("fixtures/uat/versions/payment-service-v2.patch").toAbsolutePath();

        applyPatch(paymentRoot, patch);

        assertThat(Files.readString(paymentRoot.resolve("src/main/java/com/example/payment/PaymentMethod.java")))
                .contains("MOBILE_PAYMENT");
        assertThat(Files.readString(paymentRoot.resolve("src/main/java/com/example/payment/PaymentQueryController.java")))
                .contains("PaymentMethod.MOBILE_PAYMENT");
        assertThatThrownBy(() -> applyPatch(paymentRoot, patch))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("payment-service-v2.patch");
    }

    @Test
    void exports_framework_api_contract_stub_fixture_with_complete_isolated_generations_after_starting_a_real_jdt_language_server()
            throws IOException {
        Path jdtLsHome = requiredJdtLsHome();
        DefaultJdtWorkspaceManager manager = manager(jdtLsHome);
        try (GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8.0.4")).withExposedPorts(27017)) {
            mongo.start();
            org.springframework.data.mongodb.core.MongoTemplate template = new org.springframework.data.mongodb.core.MongoTemplate(
                    com.mongodb.client.MongoClients.create("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)), "fixture_index");
            new IndexSchemaBootstrap(template).bootstrap();
            List<SourceIndexBatch> payment = exportAndPersist(manager, template, PAYMENT_FIXTURE, "payment-service", "a".repeat(40), "payment-generation");
            List<SourceIndexBatch> video = exportAndPersist(manager, template, VIDEO_FIXTURE, "video-service", "b".repeat(40), "video-generation");
            List<SourceIndexBatch> order = exportAndPersist(manager, template, ORDER_FIXTURE, "order-service", "c".repeat(40), "order-generation");
            publish(template, "payment-service", "a".repeat(40), "payment-generation");
            publish(template, "video-service", "b".repeat(40), "video-generation");
            publish(template, "order-service", "c".repeat(40), "order-generation");

            assertThat(video).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                    .contains("VideoFormat", "MP4", "WEBM", "MOV", "upload");
            assertThat(payment).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                    .contains("CREDIT_CARD", "BANK_TRANSFER", "WALLET", "calculate", "paymentMethods",
                            "FeeFormulaUnavailableException");
            assertThat(order).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                    .contains("Order", "PENDING", "CONFIRMED", "SHIPPED", "CANCELLED", "cancel");
            assertThat(payment).flatMap(SourceIndexBatch::entryPoints).extracting(document -> document.trigger().httpPath().orElseThrow())
                    .contains("/payment-methods");
            assertThat(template.getCollection(IndexCollections.ENTRY_POINTS).countDocuments(new Document("repoId", "video-service")
                    .append("generationId", "video-generation").append("path", "/videos"))).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "payment-service")
                    .append("generationId", "payment-generation").append("name", "CREDIT_CARD"))).isEqualTo(1L);
            assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "order-service")
                    .append("generationId", "payment-generation"))).isZero();
            assertThat(video).flatMap(SourceIndexBatch::relations).extracting(document -> document.kind().name())
                    .contains("IMPLEMENTS", "OVERRIDES", "CALLS_OUTBOUND_API", "PUBLISHES_MESSAGE");
            List<RelationDocument> videoRelations = video.stream().flatMap(batch -> batch.relations().stream()).toList();
            RelationDocument videoDestination = videoRelations.stream()
                    .filter(document -> document.kind() == RelationKind.PUBLISHES_MESSAGE)
                    .filter(document -> document.target() instanceof RelationTarget.External external
                            && external.target().equals(new ExternalTarget.Destination("kafka", "video.uploaded")))
                    .findFirst().orElseThrow();
            assertThat(videoDestination.range().sourceFile())
                    .isEqualTo("src/main/java/com/example/video/VideoEventPublisher.java");
            assertThat(videoDestination.range().range().start().line()).isEqualTo(8);
            assertThat(videoDestination.range().range().start().character()).isEqualTo(27);
            RelationDocument videoEndpoint = videoRelations.stream()
                    .filter(document -> document.kind() == RelationKind.CALLS_OUTBOUND_API)
                    .filter(document -> document.target() instanceof RelationTarget.External external
                            && external.target().equals(new ExternalTarget.Endpoint("POST", "/transcoding/jobs")))
                    .findFirst().orElseThrow();
            assertThat(videoEndpoint.range().sourceFile())
                    .isEqualTo("src/main/java/com/example/video/DefaultVideoService.java");
            assertThat(videoEndpoint.range().range().start().line()).isEqualTo(8);
            assertThat(videoEndpoint.range().range().start().character()).isEqualTo(8);
            assertThat(payment).flatMap(SourceIndexBatch::relations).extracting(document -> document.kind().name())
                    .contains("READS_CONFIGURATION", "USES_SQL_IDENTIFIER", "USES_TYPE", "REFERENCES", "IMPLEMENTS", "OVERRIDES", "CALLS");
            List<Document> paymentRelations = template.getCollection(IndexCollections.RELATIONS)
                    .find(new Document("repoId", "payment-service").append("generationId", "payment-generation"))
                    .into(new java.util.ArrayList<>());
            Document paymentCall = paymentRelations.stream()
                    .filter(document -> document.getString("from").contains("PaymentFeeCalculator")
                            && document.getString("target").contains("FeeFormulaEvaluator")
                            && "CALLS".equals(document.getString("kind")))
                    .findFirst().orElseThrow();
            assertThat(template.getCollection(IndexCollections.RELATIONS).countDocuments(new Document("from", paymentCall.getString("from"))))
                    .isPositive();
            assertThat(template.getCollection(IndexCollections.RELATIONS).countDocuments(new Document("target", paymentCall.getString("target"))))
                    .isPositive();
            assertThat(template.getCollection(IndexCollections.RELATIONS).find(new Document("repoId", "video-service")
                    .append("generationId", "video-generation")).into(new java.util.ArrayList<>()))
                    .allSatisfy(document -> {
                        assertThat(document.getString("sourcePath")).isNotBlank();
                        assertThat(document.get("from")).isNotNull();
                        assertThat(document.get("target")).isNotNull();
                    });
            assertThat(template.getCollection(IndexCollections.SEARCH).find(new Document()).into(new java.util.ArrayList<>()))
                    .allSatisfy(document -> assertThat(document.getString("sourcePath")).isNotBlank());
        } finally {
            manager.shutdownAll();
        }
    }

    private static void publish(org.springframework.data.mongodb.core.MongoTemplate template, String repositoryId,
                                String revision, String generationId) {
        RepositoryId id = new RepositoryId(repositoryId);
        GenerationId generation = new GenerationId(generationId);
        GenerationWriteContext lease = new GenerationWriteContext(id, generation, generationId + "-job");
        GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease,
                new RepositoryRevision(revision), new RepositoryRevision(revision));

        assertThat(result.valid()).as("validation issues: %s", result.issues()).isTrue();
        new GenerationValidator(template).recordValid(lease, result);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        writer.seal(lease, result.identityDigest().value());
        new MongoPublicationWriter(template).publish(new PublishGenerationCommand(id, new RepositoryRevision(revision), generation,
                generationId + "-job", java.util.Optional.empty(), new ManifestDigest(result.identityDigest().value())));
    }

    private List<SourceIndexBatch> exportAndPersist(DefaultJdtWorkspaceManager manager,
                                                     org.springframework.data.mongodb.core.MongoTemplate template, Path fixture,
                                                     String repositoryName, String revisionValue, String generationValue) throws IOException {
        Path repositoryRoot = copyFixture(fixture, temporaryDirectory.resolve(repositoryName));
        RepositoryId repositoryId = new RepositoryId(repositoryName);
        RepositoryRevision revision = new RepositoryRevision(revisionValue);
        GenerationId generationId = new GenerationId(generationValue);
        manager.getOrStart(new RepositorySnapshot(repositoryId, repositoryRoot, revision));
        List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter(new Lsp4jJavaSemanticService(manager)).export(repositoryId, revision, generationId,
                new FullIndexPlanner().plan(repositoryRoot));
        seedWritableGeneration(template, repositoryId, revision, generationId);
        GenerationWriteContext lease = new GenerationWriteContext(repositoryId, generationId, generationValue + "-job");
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(new MongoGenerationWriter(template), lease,
                new SourceIndexBatchDocumentMapper(template.getConverter()));
        batches.forEach(writer::write);
        return batches;
    }

    private static void seedWritableGeneration(org.springframework.data.mongodb.core.MongoTemplate template,
                                               RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId) {
        String jobId = generationId.value() + "-job";
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId)
                .append("repoId", repositoryId.value()).append("target", new Document("revision", revision.value())
                        .append("generationId", generationId.value()).append("generation", 1L)).append("operation", "BUILD")
                .append("phase", "RUNNING").append("active", true).append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        new MongoGenerationWriter(template).insertManifest(new GenerationWriteContext(repositoryId, generationId, jobId),
                new Document("repoId", repositoryId.value()).append("generationId", generationId.value()).append("ownerJobId", jobId)
                .append("sourceRevision", revision.value()).append("writeState", GenerationWriteState.WRITING.name()).append("writeEpoch", 0L)
                .append("schemaVersion", com.java.semantic.model.index.IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", com.java.semantic.model.index.IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
    }

    private Path requiredJdtLsHome() {
        String configuredHome = System.getenv("JDTLS_HOME");
        assumeTrue(StringUtils.hasText(configuredHome), "JDTLS_HOME must be configured for real JDT LS integration tests");
        Path home = Path.of(configuredHome);
        assumeTrue(Files.isDirectory(home), "JDTLS_HOME must point at an installed JDT LS directory");
        return home;
    }

    private DefaultJdtWorkspaceManager manager(Path home) {
        JdtLsProperties properties = new JdtLsProperties(true, home, temporaryDirectory.resolve("workspace"),
                Duration.ofSeconds(180), Duration.ofSeconds(600), Duration.ofSeconds(60), 1,
                Duration.ofMinutes(30), Duration.ofMinutes(1), "2g");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics metrics = new JdtWorkspaceLifecycleMetrics(registry);
        return new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties), new JdtLsReadinessProbe(properties),
                properties, registry, System::nanoTime, metrics);
    }

    private static Path copyFixture(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relativePath = source.relativize(path);
                if (relativePath.startsWith(Path.of("target"))) {
                    continue;
                }
                Path destination = target.resolve(relativePath.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    private static void applyPatch(Path fixtureRoot, Path patch) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "apply", "--check", patch.toString())
                .directory(fixtureRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String checkOutput = new String(process.getInputStream().readAllBytes());
        int checkExit = process.waitFor();
        if (checkExit != 0) {
            throw new AssertionError("payment-service-v2.patch cannot be applied: " + checkOutput);
        }
        Process apply = new ProcessBuilder("git", "apply", patch.toString())
                .directory(fixtureRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String applyOutput = new String(apply.getInputStream().readAllBytes());
        int applyExit = apply.waitFor();
        if (applyExit != 0) {
            throw new AssertionError("payment-service-v2.patch cannot be applied: " + applyOutput);
        }
    }
}
