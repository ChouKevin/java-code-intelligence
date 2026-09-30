package com.java.semantic.indexer.review;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.SemanticQueryApplication;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.bson.Document;
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
    void extracts_distinct_real_semantics_for_first_parent_review_and_keeps_review_pinned_after_current_moves() throws Exception {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4");
             SemanticReviewFixture fixture = SemanticReviewFixture.create(temporaryDirectory, mongo, jdtLsHome)) {
            ReviewManifestDocument review = fixture.prepareCurrentTo(fixture.revisionB());
            ReviewId reviewId = review.reviewId();

            assertThat(fixture.implementationNames(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "Gateway.pay"))
                    .containsExactly("LegacyGateway");
            assertThat(fixture.implementationNames(reviewId, ReviewSide.AFTER, fixture.revisionB(), "Gateway.pay"))
                    .containsExactly("ModernGateway");
            assertThat(fixture.callerNames(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "LegacyGateway.pay"))
                    .containsExactly("Checkout.place", "LegacyGateway.authorize");
            assertThat(fixture.callerNames(reviewId, ReviewSide.AFTER, fixture.revisionB(), "ModernGateway.pay"))
                    .containsExactly("Checkout.place", "ModernGateway.authorize");
            assertThat(fixture.methodSource(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "place"))
                    .contains("LegacyGateway", "LegacyReceipt");
            assertThat(fixture.methodSource(reviewId, ReviewSide.AFTER, fixture.revisionB(), "place"))
                    .contains("ModernGateway", "ModernReceipt");
            assertThat(fixture.referenceContainerNames(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "LegacyReceipt"))
                    .contains("Checkout.place");
            assertThat(fixture.referenceContainerNames(reviewId, ReviewSide.AFTER, fixture.revisionB(), "ModernReceipt"))
                    .contains("Checkout.place");
            assertThat(fixture.methodSource(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "authorize"))
                    .contains("int amount");
            assertThat(fixture.methodSource(reviewId, ReviewSide.AFTER, fixture.revisionB(), "authorize"))
                    .contains("String amount");
            assertThat(fixture.hasFact(reviewId, ReviewSide.BEFORE, fixture.revisionA(), "LegacyMarker", CodeFactKind.TYPE)).isTrue();
            assertThat(fixture.hasFact(reviewId, ReviewSide.AFTER, fixture.revisionB(), "ModernMarker", CodeFactKind.TYPE)).isTrue();
            assertThat(fixture.hasFact(reviewId, ReviewSide.AFTER, fixture.revisionB(), "LegacyGateway", CodeFactKind.TYPE)).isFalse();
            assertThat(fixture.apiRouteHandlers(reviewId, ReviewSide.BEFORE, fixture.revisionA()))
                    .containsExactly("CheckoutController.submit");
            assertThat(fixture.apiRouteHandlers(reviewId, ReviewSide.AFTER, fixture.revisionB()))
                    .containsExactly("CheckoutController.submit");
            assertThat(review.comparisonId()).isPresent();
            assertThat(fixture.directPatch(review)).contains("LegacyGateway", "ModernGateway");
            assertThat(fixture.comparison(review).ancestry()).isEqualTo("PREVIOUS_ANCESTOR");
            assertThat(fixture.classpathDigests(review.before().orElseThrow().generation().fingerprint().inputs()))
                    .isNotEqualTo(fixture.classpathDigests(review.after().orElseThrow().generation().fingerprint().inputs()));

            String reviewAfterGeneration = review.after().orElseThrow().generation().selected().generationId().value();
            fixture.publishCurrent(fixture.revisionB());
            String rebuiltCurrentGeneration = fixture.rebuildCurrent();
            assertThat(rebuiltCurrentGeneration).isNotEqualTo(reviewAfterGeneration);
            assertThat(fixture.readManifest(reviewId).after().orElseThrow().generation().selected().generationId().value()).isEqualTo(reviewAfterGeneration);
            assertThat(fixture.currentGeneration()).isEqualTo(rebuiltCurrentGeneration);

            ReviewManifestDocument equal = fixture.prepareRange(fixture.revisionB(), fixture.revisionB());
            assertThat(fixture.comparison(equal).items()).isEmpty();
            assertThat(equal.before().orElseThrow().generation().selected().generationId()).isEqualTo(equal.after().orElseThrow().generation().selected().generationId());
            assertThat(equal.before().orElseThrow().snapshotId()).isNotEqualTo(equal.after().orElseThrow().snapshotId());

            ReviewManifestDocument divergent = fixture.prepareRange(fixture.revisionB(), fixture.revisionC());
            assertThat(fixture.comparison(divergent).comparisonContext().before().revision()).contains(fixture.revisionB());
            assertThat(fixture.comparison(divergent).comparisonContext().after().revision()).contains(fixture.revisionC());
            assertThat(fixture.comparison(divergent).ancestry()).isEqualTo("DIVERGED");
            assertThat(fixture.directPatch(divergent)).contains("divergent");
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
        private final Path effectiveDependency;
        private final ConfigurableApplicationContext indexer;
        private final ConfigurableApplicationContext query;
        private final String indexerBase;
        private final SemanticQueryFacade reviews;
        private final MongoTemplate template;
        private final JsonMapper mapper = JsonMapper.builder().build();

        private SemanticReviewFixture(Path root, MongoDBContainer mongo, Git bare, Git seed, String revisionA, String revisionB,
                                      String revisionC, Path effectiveDependency, ConfigurableApplicationContext indexer,
                                      ConfigurableApplicationContext query, String indexerBase, SemanticQueryFacade reviews,
                                      MongoTemplate template) {
            this.root = root;
            this.mongo = mongo;
            this.bare = bare;
            this.seed = seed;
            this.revisionA = revisionA;
            this.revisionB = revisionB;
            this.revisionC = revisionC;
            this.effectiveDependency = effectiveDependency;
            this.indexer = indexer;
            this.query = query;
            this.indexerBase = indexerBase;
            this.reviews = reviews;
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
            Path effectiveDependency = temporaryDirectory.resolve("effective-inputs/external-review-dependency.jar");
            writeJar(effectiveDependency, "example.dependency.ExternalReviewDependency", "first");
            Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call();
            Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call();
            String revisionA = commitA(seed, seedPath, remote, effectiveDependency);
            String revisionB = commitB(seed, seedPath, effectiveDependency);
            String revisionC = commitDivergent(seed, seedPath, revisionA);
            RefUpdate head = bare.getRepository().updateRef(Constants.HEAD, true);
            head.link(Constants.R_HEADS + "main");
            Path checkoutParent = Files.createDirectories(temporaryDirectory.resolve("checkouts")).toRealPath();

            int indexerPort = availablePort();
            ConfigurableApplicationContext indexer = new SpringApplicationBuilder(SemanticIndexerApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + indexerPort,
                            "--semantic.indexer.admin-token=" + ADMIN_TOKEN, "--semantic.repositories." + REPOSITORY_ID + ".url=" + remote.toUri(),
                            "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main",
                            "--semantic.data-root=" + checkoutParent, "--semantic.jdtls.home=" + jdtLsHome,
                            "--semantic.jdtls.workspace-data-root=" + temporaryDirectory.resolve("jdt-workspace"),
                            "--semantic.jdtls.java-executable=" + Path.of(System.getProperty("java.home"), "bin", "java"),
                            "--semantic.jdtls.isolation-mode=LOCAL_TRUSTED", "--semantic.index-jobs.poll-delay=20ms",
                            "--spring.autoconfigure.exclude=org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
                            "--spring.ai.mcp.server.enabled=false", "--spring.main.banner-mode=off");
            String indexerBase = "http://127.0.0.1:" + indexerPort;
            Optional<ConfigurableApplicationContext> query = Optional.empty();
            try {
                Path queryConfig = Path.of("../semantic-query/src/main/resources/application.yml").toAbsolutePath();
                ConfigurableApplicationContext queryContext = new SpringApplicationBuilder(SemanticQueryApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.config.location=" + queryConfig.toUri(), "--spring.mongodb.uri=" + mongoUri,
                                "--semantic.query.git-evidence.allowed-repositories[0]=" + REPOSITORY_ID,
                                "--spring.ai.mcp.server.enabled=false",
                                "--spring.main.banner-mode=off");
                query = Optional.of(queryContext);
                SemanticReviewFixture fixture = new SemanticReviewFixture(temporaryDirectory, mongo, bare, seed, revisionA, revisionB,
                        revisionC, effectiveDependency, indexer, queryContext, indexerBase, queryContext.getBean(SemanticQueryFacade.class),
                        queryContext.getBean(MongoTemplate.class));
                fixture.publishCurrent(revisionA);
                return fixture;
            } catch (Exception | AssertionError exception) {
                indexer.close();
                query.ifPresent(ConfigurableApplicationContext::close);
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
            return prepare(Map.of("kind", "COMMIT", "revision", revision));
        }

        ReviewManifestDocument prepareRange(String before, String after) throws Exception {
            return prepare(Map.of("kind", "RANGE", "beforeRevision", before, "afterRevision", after));
        }

        private ReviewManifestDocument prepare(Map<String, String> selection) throws Exception {
            String jobId = accepted(post("/index/repositories/" + REPOSITORY_ID + "/reviews",
                    Map.of("requestId", java.util.UUID.randomUUID().toString(), "selection", selection)));
            Map<?, ?> completed = complete(jobId);
            return readManifest(new ReviewId(text(map(completed, "review"), "reviewId")));
        }

        private static SemanticQueryContract.PageRequest page() {
            return new SemanticQueryContract.PageRequest(Optional.empty(), 100);
        }

        private static SemanticQueryContract.ReadContext context(ReviewId id, ReviewSide side, String revision) {
            return SemanticQueryContract.ReadContext.review(REPOSITORY_ID, id.value(), side, revision);
        }

        private SemanticQueryContract.RelationCollection relations(ReviewId id, ReviewSide side, String revision,
                String factId, SemanticQueryContract.RelationMode mode) {
            return reviews.findRelations(new SemanticQueryContract.RelationRequest(context(id, side, revision), mode, factId, page()));
        }

        List<String> callerNames(ReviewId id, ReviewSide side, String revision, String targetName) {
            return relations(id, side, revision, methodFact(id, side, revision, targetName), SemanticQueryContract.RelationMode.CALLERS)
                    .items().stream().map(item -> shortMethodName(item.origin().canonical().orElseThrow())).sorted().toList();
        }

        List<String> implementationNames(ReviewId id, ReviewSide side, String revision, String methodName) {
            return relations(id, side, revision, methodFact(id, side, revision, methodName), SemanticQueryContract.RelationMode.IMPLEMENTATIONS)
                    .items().stream().map(item -> declaringType(item.origin().canonical().orElseThrow())).sorted().toList();
        }

        String methodSource(ReviewId id, ReviewSide side, String revision, String methodName) {
            return reviews.readSource(new SemanticQueryContract.SourceRequest(context(id, side, revision),
                    new SemanticQueryContract.SourceTarget(SemanticQueryContract.SourceTargetKind.FACT,
                            Optional.of(methodFact(id, side, revision, methodName)), Optional.empty(), Optional.empty(), Optional.of(0)),
                    200, Optional.empty())).content().orElseThrow();
        }

        List<String> referenceContainerNames(ReviewId id, ReviewSide side, String revision, String typeName) {
            return relations(id, side, revision, fact(id, side, revision, typeName, CodeFactKind.TYPE), SemanticQueryContract.RelationMode.REFERENCES)
                    .items().stream().map(item -> shortMethodName(item.origin().canonical().orElseThrow())).sorted().toList();
        }

        List<String> apiRouteHandlers(ReviewId id, ReviewSide side, String revision) {
            return reviews.listEntryPoints(new SemanticQueryContract.EntryPointRequest(context(id, side, revision),
                    Optional.of(SemanticQueryContract.EntryKind.HTTP), Optional.empty(), Optional.empty(),
                    Optional.of(SemanticQueryContract.HttpMethod.POST), Optional.of("/checkout/submit"), Optional.empty(),
                    Optional.empty(), Optional.empty(), page())).items().stream()
                    .map(item -> shortMethodName(item.handler().canonical().orElseThrow())).toList();
        }

        String directPatch(ReviewManifestDocument review) {
            SemanticQueryContract.ComparisonResult comparison = comparison(review);
            String change = comparison.items().stream()
                    .filter(item -> item.after().map(endpoint -> endpoint.path().equals("src/main/java/example/Checkout.java")).orElse(false))
                    .findFirst().orElseThrow().changeId();
            return reviews.getFileDiff(new SemanticQueryContract.FileDiffRequest(comparison.comparisonContext(), change, Optional.empty()))
                    .patch().orElseThrow();
        }

        SemanticQueryContract.ComparisonResult comparison(ReviewManifestDocument review) {
            return reviews.compareRevisions(new SemanticQueryContract.ComparisonRequest(
                    reviewDetails(review.reviewId()).comparisonContext().orElseThrow(), page()));
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
            return template.getCollection(IndexCollections.REPOSITORIES)
                    .find(new Document("repoId", REPOSITORY_ID)).first()
                    .get("currentPointer", Document.class).getString("generationId");
        }

        void replaceEffectiveDependency() throws IOException {
            writeJar(effectiveDependency, "example.dependency.ExternalReviewDependency", "replacement");
        }

        SemanticQueryContract.ReviewContextResult reviewDetails(ReviewId id) {
            return (SemanticQueryContract.ReviewContextResult) reviews.getContext(new SemanticQueryContract.ContextRequest(REPOSITORY_ID,
                    new SemanticQueryContract.ContextSelector(SemanticQueryContract.SelectorKind.REVIEW, Optional.of(id.value()),
                            Optional.empty(), Optional.empty(), Optional.empty()), 100));
        }

        private String methodFact(ReviewId id, ReviewSide side, String revision, String name) {
            int ownerSeparator = name.lastIndexOf('.');
            String methodName = ownerSeparator < 0 ? name : name.substring(ownerSeparator + 1);
            String owner = ownerSeparator < 0 ? "" : name.substring(0, ownerSeparator);
            SemanticQueryContract.FactCollection result = reviews.searchCode(
                    new SemanticQueryContract.SearchCodeRequest(context(id, side, revision), methodName,
                            Set.of(CodeFactKind.METHOD), Optional.empty(), Optional.empty(), page()));
            return result.items().stream()
                    .filter(item -> item.displayName().contains(methodName)
                            && (owner.isEmpty() || item.canonical().orElseThrow().contains(owner)))
                    .findFirst().orElseThrow(() -> new AssertionError("missing method fact: " + name)).factId();
        }

        private String fact(ReviewId id, ReviewSide side, String revision, String search, CodeFactKind kind) {
            SemanticQueryContract.FactCollection result = reviews.searchCode(
                    new SemanticQueryContract.SearchCodeRequest(context(id, side, revision), search, Set.of(kind),
                            Optional.empty(), Optional.empty(), page()));
            return result.items().stream().filter(item -> item.displayName().contains(search))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + kind + " fact: " + search)).factId();
        }

        boolean hasFact(ReviewId id, ReviewSide side, String revision, String search, CodeFactKind kind) {
            SemanticQueryContract.FactCollection result = reviews.searchCode(
                    new SemanticQueryContract.SearchCodeRequest(context(id, side, revision), search, Set.of(kind),
                            Optional.empty(), Optional.empty(), page()));
            return result.items().stream().anyMatch(item -> item.displayName().contains(search));
        }

        List<String> classpathDigests(AnalysisInputs inputs) {
            return inputs.projects().stream().flatMap(project -> project.classpath().stream())
                    .map(AnalysisInputs.Artifact::contentDigest).sorted().toList();
        }

        private ReviewManifestDocument readManifest(ReviewId id) {
            return new ReviewPublicationStore(template, new ReviewReadinessValidator(template),
                    new MongoIndexJobStore(template))
                    .findReady(new RepositoryId(REPOSITORY_ID), id);
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
                HttpResponse<String> status = get("/index/repositories/" + REPOSITORY_ID + "/jobs?jobId=" + jobId);
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
            indexer.close();
            query.close();
            seed.close();
            bare.close();
            mongo.stop();
        }

        private static String commitA(Git seed, Path root, Path remote, Path effectiveDependency) throws Exception {
            writePom(root, "src/legacy/java", "legacy-receipt.jar", effectiveDependency);
            writeJar(root.resolve("lib/legacy-receipt.jar"), "example.dependency.LegacyReceiptDependency");
            write(root, "src/main/resources/.gitkeep", "");
            write(root, "src/main/java/example/Gateway.java", "package example; public interface Gateway { void pay(); }\n");
            write(root, "src/main/java/example/Checkout.java", "package example; import example.dependency.ExternalReviewDependency; import example.dependency.LegacyReceiptDependency; public class Checkout { public void place() { LegacyGateway gateway = new LegacyGateway(); gateway.authorize(7); gateway.pay(); new LegacyReceiptDependency().label(); new ExternalReviewDependency().label(); LegacyReceipt receipt = new LegacyReceipt(); } }\n");
            write(root, "src/main/java/example/CheckoutController.java", "package example; @RequestMapping(\"/checkout\") public class CheckoutController { @PostMapping(\"/submit\") public void submit() { new Checkout().place(); } }\n");
            write(root, "src/main/java/example/RequestMapping.java", "package example; public @interface RequestMapping { String value(); }\n");
            write(root, "src/main/java/example/PostMapping.java", "package example; public @interface PostMapping { String value(); }\n");
            write(root, "src/main/java/example/LegacyGateway.java", "package example; public class LegacyGateway implements Gateway { public void pay() { } public void authorize(int amount) { pay(); } }\n");
            write(root, "src/main/java/example/LegacyReceipt.java", "package example; public class LegacyReceipt { }\n");
            write(root, "src/legacy/java/example/LegacyMarker.java", "package example; public class LegacyMarker { }\n");
            return commitAndPush(seed, remote, "legacy gateway");
        }

        private static String commitB(Git seed, Path root, Path effectiveDependency) throws Exception {
            Files.delete(root.resolve("lib/legacy-receipt.jar"));
            Files.delete(root.resolve("src/main/java/example/LegacyGateway.java"));
            Files.delete(root.resolve("src/main/java/example/LegacyReceipt.java"));
            deleteTree(root.resolve("src/legacy/java"));
            writePom(root, "src/modern/java", "modern-receipt.jar", effectiveDependency);
            writeJar(root.resolve("lib/modern-receipt.jar"), "example.dependency.ModernReceiptDependency");
            write(root, "src/main/java/example/Checkout.java", "package example; import example.dependency.ExternalReviewDependency; import example.dependency.ModernReceiptDependency; public class Checkout { public void place() { ModernGateway gateway = new ModernGateway(); gateway.authorize(\"7\"); gateway.pay(); new ModernReceiptDependency().label(); new ExternalReviewDependency().label(); ModernReceipt receipt = new ModernReceipt(); } }\n");
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
            if (Objects.nonNull(remote)) {
                seed.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toString())).call();
            }
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
            return revision;
        }

        private static void writePom(Path root, String sourceRoot, String dependency, Path effectiveDependency) throws IOException {
            write(root, "pom.xml", """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId><artifactId>semantic-review-fixture</artifactId><version>1</version>
                      <properties><maven.compiler.release>21</maven.compiler.release></properties>
                      <dependencies>
                        <dependency><groupId>example</groupId><artifactId>receipt</artifactId><version>1</version><scope>system</scope><systemPath>${project.basedir}/lib/%s</systemPath></dependency>
                        <dependency><groupId>example</groupId><artifactId>external-review</artifactId><version>1</version><scope>system</scope><systemPath>%s</systemPath></dependency>
                      </dependencies>
                      <build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.1</version><executions><execution><id>custom-root</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals><configuration><sources><source>%s</source></sources></configuration></execution></executions></plugin></plugins></build>
                    </project>
                    """.formatted(dependency, effectiveDependency, sourceRoot));
        }

        private static void writeJar(Path path, String className) throws IOException {
            writeJar(path, className, className);
        }

        private static void writeJar(Path path, String className, String label) throws IOException {
            Files.createDirectories(path.getParent());
            Path scratch = Files.createTempDirectory(path.getParent(), "dependency");
            try {
                Path source = scratch.resolve("source").resolve(className.replace('.', '/') + ".java");
                Path classes = scratch.resolve("classes");
                Files.createDirectories(source.getParent());
                Files.writeString(source, "package " + className.substring(0, className.lastIndexOf('.'))
                        + "; public final class " + className.substring(className.lastIndexOf('.') + 1)
                        + " { public String label() { return \"" + label + "\"; } }\n");
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (Objects.isNull(compiler) || compiler.run(null, null, null, "-d", classes.toString(), source.toString()) != 0) {
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
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
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
