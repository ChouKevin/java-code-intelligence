package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
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
import org.testcontainers.containers.MongoDBContainer;
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
    void exports_framework_api_contract_stub_fixture_with_complete_isolated_generations_after_starting_a_real_jdt_language_server()
            throws IOException {
        Path jdtLsHome = requiredJdtLsHome();
        DefaultJdtWorkspaceManager manager = manager(jdtLsHome);
        try (MongoDBContainer mongo = new MongoDBContainer("mongo:7.0.5")) {
            mongo.start();
            org.springframework.data.mongodb.core.MongoTemplate template = new org.springframework.data.mongodb.core.MongoTemplate(
                    com.mongodb.client.MongoClients.create(mongo.getConnectionString()), "fixture_index");
            new IndexSchemaBootstrap(template).bootstrap();
            List<SourceIndexBatch> payment = exportAndPersist(manager, template, PAYMENT_FIXTURE, "payment-service", "a".repeat(40), "payment-generation");
            List<SourceIndexBatch> video = exportAndPersist(manager, template, VIDEO_FIXTURE, "video-service", "b".repeat(40), "video-generation");
            exportAndPersist(manager, template, ORDER_FIXTURE, "order-service", "c".repeat(40), "order-generation");

            assertThat(video).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                    .contains("VideoFormat", "MP4", "WEBM", "MOV", "upload");
            assertThat(payment).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                    .contains("CREDIT_CARD", "BANK_TRANSFER", "WALLET", "calculate", "paymentMethods");
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
        seedWritableGeneration(template, repositoryId, generationId);
        MongoGenerationWriter.GenerationLease lease = new MongoGenerationWriter.GenerationLease(repositoryId, generationId,
                generationValue + "-job", "fixture-worker", 1L);
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(new MongoGenerationWriter(template), lease,
                new SourceIndexBatchDocumentMapper(template.getConverter()));
        batches.forEach(writer::write);
        return batches;
    }

    private static void seedWritableGeneration(org.springframework.data.mongodb.core.MongoTemplate template,
                                               RepositoryId repositoryId, GenerationId generationId) {
        java.util.Date expiry = java.util.Date.from(java.time.Instant.now().plus(Duration.ofMinutes(5)));
        String jobId = generationId.value() + "-job";
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId.value())
                .append("activeJobId", jobId).append("activeWorkerId", "fixture-worker")
                .append("activeGenerationId", generationId.value()).append("fence", 1L).append("claimUntil", expiry));
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId)
                .append("repoId", repositoryId.value()).append("active", true));
        new MongoGenerationWriter(template).insertManifest(new Document("repoId", repositoryId.value())
                .append("generationId", generationId.value()).append("ownerJobId", jobId).append("ownerWorkerId", "fixture-worker")
                .append("fence", 1L).append("writeState", GenerationWriteState.WRITING.name()).append("sealUntil", expiry));
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
                Path destination = target.resolve(source.relativize(path).toString());
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
}
