package com.java.semantic.indexer.review;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.SemanticQueryApplication;
import com.java.semantic.query.application.ReviewQueryContract;
import com.java.semantic.query.application.ReviewQueryFacade;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.semantic.adapter.jdtls.JdtLsHomeRequirement;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves review evidence originates from the real JDT extractor, then is read through production review readers. */
@Tag("jdtls-it")
class SemanticReviewJdtLsIT {
    private static final String REPOSITORY_ID = "semantic-review-fixture";
    private static final String ADMIN_TOKEN = "semantic-review-admin";
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(3);

    @TempDir
    Path temporaryDirectory;

    @Test
    void extracts_distinct_real_semantics_for_current_to_commit_review_and_keeps_review_pinned_after_current_moves() throws Exception {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4");
             SemanticReviewFixture fixture = SemanticReviewFixture.create(temporaryDirectory, mongo, jdtLsHome)) {
            ReviewManifestDocument review = fixture.prepareCurrentTo(fixture.revisionB());
            ReviewId reviewId = review.reviewId();

            assertThat(fixture.implementationNames(reviewId, ReviewSide.A, fixture.revisionA(), "Gateway.pay"))
                    .containsExactly("LegacyGateway");
            assertThat(fixture.implementationNames(reviewId, ReviewSide.B, fixture.revisionB(), "Gateway.pay"))
                    .containsExactly("ModernGateway");
            assertThat(fixture.callerNames(reviewId, ReviewSide.A, fixture.revisionA(), "LegacyGateway.pay"))
                    .containsExactly("Checkout.place", "LegacyGateway.authorize");
            assertThat(fixture.callerNames(reviewId, ReviewSide.B, fixture.revisionB(), "ModernGateway.pay"))
                    .containsExactly("Checkout.place", "ModernGateway.authorize");
            assertThat(fixture.methodSource(reviewId, ReviewSide.A, fixture.revisionA(), "place"))
                    .contains("LegacyGateway", "LegacyReceipt");
            assertThat(fixture.methodSource(reviewId, ReviewSide.B, fixture.revisionB(), "place"))
                    .contains("ModernGateway", "ModernReceipt");
            assertThat(fixture.referenceContainerNames(reviewId, ReviewSide.A, fixture.revisionA(), "LegacyReceipt"))
                    .contains("Checkout.place");
            assertThat(fixture.referenceContainerNames(reviewId, ReviewSide.B, fixture.revisionB(), "ModernReceipt"))
                    .contains("Checkout.place");
            assertThat(fixture.methodSource(reviewId, ReviewSide.A, fixture.revisionA(), "authorize"))
                    .contains("int amount");
            assertThat(fixture.methodSource(reviewId, ReviewSide.B, fixture.revisionB(), "authorize"))
                    .contains("String amount");
            assertThat(fixture.hasFact(reviewId, ReviewSide.A, fixture.revisionA(), "LegacyMarker", CodeFactKind.TYPE)).isTrue();
            assertThat(fixture.hasFact(reviewId, ReviewSide.B, fixture.revisionB(), "ModernMarker", CodeFactKind.TYPE)).isTrue();
            assertThat(fixture.hasFact(reviewId, ReviewSide.B, fixture.revisionB(), "LegacyGateway", CodeFactKind.TYPE)).isFalse();
            assertThat(fixture.apiRouteHandlers(reviewId, ReviewSide.A, fixture.revisionA()))
                    .containsExactly("CheckoutController.submit");
            assertThat(fixture.apiRouteHandlers(reviewId, ReviewSide.B, fixture.revisionB()))
                    .containsExactly("CheckoutController.submit");
            assertThat(review.comparisonId()).isPresent();
            assertThat(fixture.directPatch(review)).contains("LegacyGateway", "ModernGateway");
            assertThat(fixture.comparison(review).ancestry()).isEqualTo("PREVIOUS_ANCESTOR");
            assertThat(fixture.classpathDigests(review.a().orElseThrow().generation().fingerprint().inputs()))
                    .isNotEqualTo(fixture.classpathDigests(review.b().orElseThrow().generation().fingerprint().inputs()));

            String reviewBGeneration = fixture.reviewDetails(reviewId).b().generationId();
            fixture.publishCurrent(fixture.revisionB());
            String rebuiltCurrentGeneration = fixture.rebuildCurrent();
            assertThat(rebuiltCurrentGeneration).isNotEqualTo(reviewBGeneration);
            assertThat(fixture.reviewDetails(reviewId).b().generationId()).isEqualTo(reviewBGeneration);
            assertThat(fixture.currentGeneration()).isEqualTo(rebuiltCurrentGeneration);

            ReviewManifestDocument sameSha = fixture.prepareCurrentTo(fixture.revisionB());
            ReviewQueryContract.ReviewDetails sameShaDetails = fixture.reviewDetails(sameSha.reviewId());
            assertThat(sameShaDetails.capturedBaseline().generationId()).isEqualTo(rebuiltCurrentGeneration);
            assertThat(fixture.comparison(sameSha).items()).isEmpty();
            assertThat(sameShaDetails.a().generationId()).isEqualTo(sameShaDetails.b().generationId());
            assertThat(sameShaDetails.a().snapshotId()).isNotEqualTo(sameShaDetails.b().snapshotId());

            fixture.publishCurrent(fixture.revisionB());
            ReviewManifestDocument divergent = fixture.prepareCurrentTo(fixture.revisionC());
            assertThat(fixture.comparison(divergent).previous()).isEqualTo(fixture.revisionB());
            assertThat(fixture.comparison(divergent).current()).isEqualTo(fixture.revisionC());
            assertThat(fixture.comparison(divergent).ancestry()).isEqualTo("DIVERGED");
            assertThat(fixture.directPatch(divergent, fixture.revisionB(), fixture.revisionC())).contains("divergent");
        }
    }

    /** Owns a disposable Git/Mongo/application fixture and exposes only production review reads to this acceptance. */
    static final class SemanticReviewFixture implements AutoCloseable {
        private final Path root;
        private final MongoDBContainer mongo;
        private final Git bare;
        private final Git seed;
        private final String revisionA;
        private final String revisionB;
        private final String revisionC;
        private final ConfigurableApplicationContext indexer;
        private final ConfigurableApplicationContext query;
        private final String indexerBase;
        private final ReviewQueryFacade reviews;
        private final SemanticQueryFacade current;
        private final MongoTemplate template;
        private final JsonMapper mapper = JsonMapper.builder().build();

        private SemanticReviewFixture(Path root, MongoDBContainer mongo, Git bare, Git seed, String revisionA, String revisionB,
                                      String revisionC, ConfigurableApplicationContext indexer, ConfigurableApplicationContext query, String indexerBase,
                                      ReviewQueryFacade reviews, SemanticQueryFacade current, MongoTemplate template) {
            this.root = root;
            this.mongo = mongo;
            this.bare = bare;
            this.seed = seed;
            this.revisionA = revisionA;
            this.revisionB = revisionB;
            this.revisionC = revisionC;
            this.indexer = indexer;
            this.query = query;
            this.indexerBase = indexerBase;
            this.reviews = reviews;
            this.current = current;
            this.template = template;
        }

        static SemanticReviewFixture create(Path temporaryDirectory, MongoDBContainer mongo, Path jdtLsHome) throws Exception {
            mongo.start();
            String mongoUri = mongo.getConnectionString() + "/semantic_review";
            try (MongoClient client = MongoClients.create(mongo.getConnectionString())) {
                MongoTemplate template = new MongoTemplate(client, "semantic_review");
                new IndexSchemaBootstrap(template).bootstrap();
            }
            Path remote = temporaryDirectory.resolve("semantic-review-remote.git");
            Path seedPath = temporaryDirectory.resolve("semantic-review-seed");
            Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call();
            Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call();
            String revisionA = commitA(seed, seedPath, remote);
            String revisionB = commitB(seed, seedPath);
            String revisionC = commitDivergent(seed, seedPath, revisionA);
            RefUpdate head = bare.getRepository().updateRef(Constants.HEAD, true);
            head.link(Constants.R_HEADS + "main");

            int indexerPort = availablePort();
            ConfigurableApplicationContext indexer = new SpringApplicationBuilder(SemanticIndexerApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + indexerPort,
                            "--semantic.indexer.admin-token=" + ADMIN_TOKEN, "--semantic.repositories." + REPOSITORY_ID + ".url=" + remote.toUri(),
                            "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main",
                            "--semantic.data-root=" + temporaryDirectory.resolve("checkouts"), "--semantic.jdtls.home=" + jdtLsHome,
                            "--semantic.jdtls.workspace-data-root=" + temporaryDirectory.resolve("jdt-workspace"),
                            "--semantic.jdtls.java-executable=" + Path.of(System.getProperty("java.home"), "bin", "java"),
                            "--semantic.jdtls.isolation-mode=LOCAL_TRUSTED", "--semantic.index-jobs.poll-delay=20ms",
                            "--spring.autoconfigure.exclude=org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
                            "--spring.ai.mcp.server.enabled=false", "--spring.main.banner-mode=off");
            String indexerBase = "http://127.0.0.1:" + indexerPort;
            try {
                Path queryConfig = Path.of("../semantic-query/src/main/resources/application.yml").toAbsolutePath();
                ConfigurableApplicationContext query = new SpringApplicationBuilder(SemanticQueryApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.config.location=" + queryConfig.toUri(), "--spring.mongodb.uri=" + mongoUri,
                                "--semantic.query.git-evidence.allowed-repositories[0]=" + REPOSITORY_ID,
                                "--spring.main.banner-mode=off");
                SemanticReviewFixture fixture = new SemanticReviewFixture(temporaryDirectory, mongo, bare, seed, revisionA, revisionB,
                        revisionC, indexer, query, indexerBase, query.getBean(ReviewQueryFacade.class), query.getBean(SemanticQueryFacade.class),
                        query.getBean(MongoTemplate.class));
                fixture.publishCurrent(revisionA);
                return fixture;
            } catch (Exception exception) {
                indexer.close();
                seed.close();
                bare.close();
                mongo.stop();
                throw exception;
            }
        }

        String revisionA() {
            return revisionA;
        }

        String revisionB() {
            return revisionB;
        }

        String revisionC() {
            return revisionC;
        }

        ReviewManifestDocument prepareCurrentTo(String revision) throws Exception {
            String jobId = accepted(post("/index/repositories/" + REPOSITORY_ID + "/reviews", Map.of("revision", revision)));
            Map<?, ?> completed = complete(jobId);
            String reviewId = text(map(completed, "review"), "reviewId");
            return readManifest(new ReviewId(reviewId));
        }

        List<String> callerNames(ReviewId id, ReviewSide side, String revision, String targetName) {
            String factId = methodFact(id, side, revision, targetName);
            ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> result = reviews.findCallers(
                    new ReviewQueryContract.ReviewRelationRequest(REPOSITORY_ID, id.value(), side, revision, factId, 0, 100));
            return result.result().items().stream().map(SemanticQueryContract.CallerItem.class::cast)
                    .map(item -> shortMethodName(item.caller().displayName())).sorted().toList();
        }

        List<String> implementationNames(ReviewId id, ReviewSide side, String revision, String methodName) {
            String factId = methodFact(id, side, revision, methodName);
            ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> result = reviews.findMethodImplementations(
                    new ReviewQueryContract.ReviewRelationRequest(REPOSITORY_ID, id.value(), side, revision, factId, 0, 100));
            return result.result().items().stream().map(SemanticQueryContract.ImplementationItem.class::cast)
                    .map(item -> declaringType(item.implementation().displayName())).sorted().toList();
        }

        String methodSource(ReviewId id, ReviewSide side, String revision, String methodName) {
            String factId = methodFact(id, side, revision, methodName);
            return reviews.getFactSource(new ReviewQueryContract.ReviewFactSourceRequest(REPOSITORY_ID, id.value(), side, revision, factId, 0))
                    .result().source().code();
        }

        List<String> referenceContainerNames(ReviewId id, ReviewSide side, String revision, String typeName) {
            String factId = fact(id, side, revision, typeName, CodeFactKind.TYPE);
            return reviews.findReferences(new ReviewQueryContract.ReviewRelationRequest(REPOSITORY_ID, id.value(), side, revision, factId, 0, 100))
                    .result().items().stream().map(SemanticQueryContract.ReferenceItem.class::cast)
                    .map(item -> shortMethodName(item.container().displayName())).sorted().toList();
        }

        List<String> apiRouteHandlers(ReviewId id, ReviewSide side, String revision) {
            return reviews.findApiRoutes(new ReviewQueryContract.ReviewApiRouteRequest(REPOSITORY_ID, id.value(), side, revision,
                    SemanticQueryContract.HttpMethod.POST, "/checkout/submit", 0, 100)).result().items().stream()
                    .map(SemanticQueryContract.EntryPointItem.class::cast).map(item -> shortMethodName(item.handler().displayName())).toList();
        }

        String directPatch(ReviewManifestDocument review) {
            return directPatch(review, revisionA, revisionB);
        }

        String directPatch(ReviewManifestDocument review, String previous, String currentRevision) {
            String comparisonId = review.comparisonId().orElseThrow().value();
            return current.getFileDiff(new SemanticQueryContract.GitFileDiffRequest(REPOSITORY_ID, comparisonId, previous, currentRevision,
                    changedCheckoutId(comparisonId, previous, currentRevision), Optional.empty())).patch();
        }

        SemanticQueryContract.GitComparisonCollection comparison(ReviewManifestDocument review) {
            String comparisonId = review.comparisonId().orElseThrow().value();
            return current.compareRevisions(new SemanticQueryContract.GitComparisonRequest(REPOSITORY_ID, comparisonId,
                    review.a().orElseThrow().generation().selected().revision().value(),
                    review.b().orElseThrow().generation().selected().revision().value(), 0, 100));
        }

        void publishCurrent(String revision) throws Exception {
            waitForCheckout(indexerBase, revision);
        }

        String rebuildCurrent() throws Exception {
            Map<?, ?> publication = response(get("/index/repositories/" + REPOSITORY_ID + "/publication"));
            Map<?, ?> pointer = map(publication, "currentPointer");
            String jobId = accepted(post("/index/repositories/" + REPOSITORY_ID + "/rebuild", Map.of(
                    "authorizeIncompatibleSchema", true, "expectedCurrent", pointer)));
            return text(map(complete(jobId), "currentPointer"), "generationId");
        }

        String currentGeneration() {
            return template.getCollection(com.java.semantic.model.index.IndexCollections.REPOSITORIES)
                    .find(new org.bson.Document("repoId", REPOSITORY_ID)).first()
                    .get("currentPointer", org.bson.Document.class).getString("generationId");
        }

        ReviewQueryContract.ReviewDetails reviewDetails(ReviewId id) {
            return reviews.getReview(new ReviewQueryContract.ReviewRequest(REPOSITORY_ID, id.value()));
        }

        private String methodFact(ReviewId id, ReviewSide side, String revision, String name) {
            int ownerSeparator = name.lastIndexOf('.');
            String methodName = ownerSeparator < 0 ? name : name.substring(ownerSeparator + 1);
            String owner = ownerSeparator < 0 ? "" : name.substring(0, ownerSeparator);
            ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> result = reviews.searchCode(
                    new ReviewQueryContract.ReviewSearchCodeRequest(REPOSITORY_ID, id.value(), side, revision, methodName,
                            Set.of(CodeFactKind.METHOD), Optional.empty(), 0, 100));
            return result.result().items().stream()
                    .filter(item -> item.displayName().contains(methodName)
                            && (owner.isEmpty() || item.displayName().contains(owner)))
                    .findFirst().orElseThrow(() -> new AssertionError("missing method fact: " + name)).factId();
        }

        private String fact(ReviewId id, ReviewSide side, String revision, String search, CodeFactKind kind) {
            ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> result = reviews.searchCode(
                    new ReviewQueryContract.ReviewSearchCodeRequest(REPOSITORY_ID, id.value(), side, revision, search, Set.of(kind), Optional.empty(), 0, 100));
            return result.result().items().stream().filter(item -> item.displayName().contains(search))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + kind + " fact: " + search)).factId();
        }

        boolean hasFact(ReviewId id, ReviewSide side, String revision, String search, CodeFactKind kind) {
            ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> result = reviews.searchCode(
                    new ReviewQueryContract.ReviewSearchCodeRequest(REPOSITORY_ID, id.value(), side, revision, search, Set.of(kind),
                            Optional.empty(), 0, 100));
            return result.result().items().stream().anyMatch(item -> item.displayName().contains(search));
        }

        List<String> classpathDigests(AnalysisInputs inputs) {
            return inputs.projects().stream().flatMap(project -> project.classpath().stream())
                    .map(AnalysisInputs.Artifact::contentDigest).sorted().toList();
        }

        private ReviewManifestDocument readManifest(ReviewId id) {
            return new ReviewPublicationStore(template, new ReviewReadinessValidator(template),
                    new com.java.semantic.indexer.job.MongoIndexJobStore(template))
                    .findReady(new com.java.semantic.model.repository.RepositoryId(REPOSITORY_ID), id);
        }

        private String changedCheckoutId(String comparisonId, String previous, String currentRevision) {
            SemanticQueryContract.GitComparisonCollection comparison = current.compareRevisions(
                    new SemanticQueryContract.GitComparisonRequest(REPOSITORY_ID, comparisonId, previous, currentRevision, 0, 100));
            return comparison.items().stream().filter(item -> "src/main/java/example/Checkout.java".equals(item.newPath()))
                    .findFirst().orElseThrow().changeId();
        }

        private void waitForCheckout(String base, String revision) throws Exception {
            String jobId = accepted(post("/index/repositories/" + REPOSITORY_ID + "/checkout", Map.of("revision", revision)));
            complete(jobId);
        }

        private String accepted(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).isEqualTo(202);
            return text(mapper.readValue(response.body(), Map.class), "jobId");
        }

        private Map<?, ?> complete(String jobId) throws Exception {
            Instant deadline = Instant.now().plus(JOB_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                HttpResponse<String> status = get("/index/repositories/" + REPOSITORY_ID + "/jobs/" + jobId);
                if (status.statusCode() == 200) {
                    Map<?, ?> body = response(status);
                    if ("COMPLETE".equals(body.get("phase"))) {
                        return body;
                    }
                    assertThat(body.get("phase")).as(status.body()).isNotEqualTo("FAILED");
                }
                Thread.sleep(100L);
            }
            throw new AssertionError("review job did not complete: " + jobId);
        }

        private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(indexerBase + path))
                    .header("X-Api-Token", ADMIN_TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> get(String path) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(indexerBase + path)).header("X-Api-Token", ADMIN_TOKEN)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        private Map<?, ?> response(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return mapper.readValue(response.body(), Map.class);
        }

        private static Map<?, ?> map(Map<?, ?> body, String field) {
            Object value = body.get(field);
            assertThat(value).isInstanceOf(Map.class);
            return (Map<?, ?>) value;
        }

        private static String text(Map<?, ?> body, String field) {
            Object value = body.get(field);
            assertThat(value).isInstanceOf(String.class);
            return (String) value;
        }

        private static String shortMethodName(String displayName) {
            int sourceMarker = displayName.indexOf('@');
            int methodMarker = displayName.lastIndexOf('#');
            if (sourceMarker >= 0 && methodMarker > sourceMarker) {
                String type = displayName.substring(0, sourceMarker);
                String method = displayName.substring(methodMarker + 1);
                int parameterMarker = method.indexOf('(');
                return type.substring(type.lastIndexOf('.') + 1) + "."
                        + (parameterMarker < 0 ? method : method.substring(0, parameterMarker));
            }
            int packageSeparator = displayName.lastIndexOf('.');
            int methodSeparator = displayName.lastIndexOf('.', packageSeparator - 1);
            return methodSeparator < 0 ? displayName : displayName.substring(methodSeparator + 1, displayName.indexOf('(', methodSeparator));
        }

        private static String declaringType(String displayName) {
            int methodSeparator = displayName.lastIndexOf('.', displayName.indexOf('('));
            int typeSeparator = displayName.lastIndexOf('.', methodSeparator - 1);
            String type = displayName.substring(typeSeparator + 1, methodSeparator);
            int sourceMarker = type.indexOf('@');
            return sourceMarker < 0 ? type : type.substring(0, sourceMarker);
        }

        @Override
        public void close() {
            query.close();
            indexer.close();
            seed.close();
            bare.close();
            mongo.stop();
        }

        private static String commitA(Git seed, Path root, Path remote) throws Exception {
            writePom(root, "src/legacy/java", "legacy-receipt.jar");
            writeJar(root.resolve("lib/legacy-receipt.jar"), "example.dependency.LegacyReceiptDependency");
            write(root, "src/main/resources/.gitkeep", "");
            write(root, "src/main/java/example/Gateway.java", "package example; public interface Gateway { void pay(); }\n");
            write(root, "src/main/java/example/Checkout.java", "package example; import example.dependency.LegacyReceiptDependency; public class Checkout { public void place() { LegacyGateway gateway = new LegacyGateway(); gateway.authorize(7); gateway.pay(); new LegacyReceiptDependency().label(); LegacyReceipt receipt = new LegacyReceipt(); } }\n");
            write(root, "src/main/java/example/CheckoutController.java", "package example; @RequestMapping(\"/checkout\") public class CheckoutController { @PostMapping(\"/submit\") public void submit() { new Checkout().place(); } }\n");
            write(root, "src/main/java/example/RequestMapping.java", "package example; public @interface RequestMapping { String value(); }\n");
            write(root, "src/main/java/example/PostMapping.java", "package example; public @interface PostMapping { String value(); }\n");
            write(root, "src/main/java/example/LegacyGateway.java", "package example; public class LegacyGateway implements Gateway { public void pay() { } public void authorize(int amount) { pay(); } }\n");
            write(root, "src/main/java/example/LegacyReceipt.java", "package example; public class LegacyReceipt { }\n");
            write(root, "src/legacy/java/example/LegacyMarker.java", "package example; public class LegacyMarker { }\n");
            return commitAndPush(seed, remote, "legacy gateway");
        }

        private static String commitB(Git seed, Path root) throws Exception {
            Files.delete(root.resolve("lib/legacy-receipt.jar"));
            Files.delete(root.resolve("src/main/java/example/LegacyGateway.java"));
            Files.delete(root.resolve("src/main/java/example/LegacyReceipt.java"));
            deleteTree(root.resolve("src/legacy/java"));
            writePom(root, "src/modern/java", "modern-receipt.jar");
            writeJar(root.resolve("lib/modern-receipt.jar"), "example.dependency.ModernReceiptDependency");
            write(root, "src/main/java/example/Checkout.java", "package example; import example.dependency.ModernReceiptDependency; public class Checkout { public void place() { ModernGateway gateway = new ModernGateway(); gateway.authorize(\"7\"); gateway.pay(); new ModernReceiptDependency().label(); ModernReceipt receipt = new ModernReceipt(); } }\n");
            write(root, "src/main/java/example/ModernGateway.java", "package example; public class ModernGateway implements Gateway { public void pay() { } public void authorize(String amount) { pay(); } }\n");
            write(root, "src/main/java/example/ModernReceipt.java", "package example; public class ModernReceipt { }\n");
            write(root, "src/modern/java/example/ModernMarker.java", "package example; public class ModernMarker { }\n");
            return commitAndPush(seed, null, "modern gateway");
        }

        private static String commitDivergent(Git seed, Path root, String revisionA) throws Exception {
            seed.checkout().setName(revisionA).call();
            write(root, "src/main/java/example/Checkout.java", "package example; import example.dependency.LegacyReceiptDependency; public class Checkout { public void place() { new LegacyGateway().pay(); } public String divergent() { return \"divergent\"; } }\n");
            seed.add().addFilepattern(".").call();
            String revision = seed.commit().setMessage("divergent gateway").setAuthor("Fixture", "fixture@example.test")
                    .setCommitter("Fixture", "fixture@example.test").call().getId().name();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:refs/heads/divergent")).call();
            seed.checkout().setName("main").call();
            return revision;
        }

        private static String commitAndPush(Git seed, Path remote, String message) throws Exception {
            seed.add().addFilepattern(".").call();
            seed.add().setUpdate(true).addFilepattern(".").call();
            String revision = seed.commit().setMessage(message).setAuthor("Fixture", "fixture@example.test")
                    .setCommitter("Fixture", "fixture@example.test").call().getId().name();
            if (remote != null) {
                seed.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toString())).call();
            }
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
            return revision;
        }

        private static void writePom(Path root, String sourceRoot, String dependency) throws IOException {
            write(root, "pom.xml", """
                    <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId><artifactId>semantic-review-fixture</artifactId><version>1</version>
                      <properties><maven.compiler.release>21</maven.compiler.release></properties>
                      <dependencies>
                        <dependency><groupId>example</groupId><artifactId>receipt</artifactId><version>1</version><scope>system</scope><systemPath>${project.basedir}/lib/%s</systemPath></dependency>
                      </dependencies>
                      <build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.1</version><executions><execution><id>custom-root</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals><configuration><sources><source>%s</source></sources></configuration></execution></executions></plugin></plugins></build>
                    </project>
                    """.formatted(dependency, sourceRoot));
        }

        private static void writeJar(Path path, String className) throws IOException {
            Files.createDirectories(path.getParent());
            Path scratch = Files.createTempDirectory(path.getParent(), "dependency");
            try {
                Path source = scratch.resolve("source").resolve(className.replace('.', '/') + ".java");
                Path classes = scratch.resolve("classes");
                Files.createDirectories(source.getParent());
                Files.writeString(source, "package " + className.substring(0, className.lastIndexOf('.'))
                        + "; public final class " + className.substring(className.lastIndexOf('.') + 1)
                        + " { public String label() { return \"" + className + "\"; } }\n");
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null || compiler.run(null, null, null, "-d", classes.toString(), source.toString()) != 0) {
                    throw new IOException("fixture dependency compilation failed");
                }
                Files.createDirectories(path.getParent());
                Path compiled = classes.resolve(className.replace('.', '/') + ".class");
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
                    output.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
                    Files.copy(compiled, output);
                    output.closeEntry();
                }
            } finally {
                deleteTree(scratch);
            }
        }

        private static void write(Path root, String path, String content) throws IOException {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        }

        private static void deleteTree(Path root) throws IOException {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }

        private static int availablePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }
    }
}
