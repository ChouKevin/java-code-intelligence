package com.java.semantic.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.query.application.SemanticQueryContract.ContextRequest;
import com.java.semantic.query.application.SemanticQueryContract.ContextSelector;
import com.java.semantic.query.application.SemanticQueryContract.ContextState;
import com.java.semantic.query.application.SemanticQueryContract.CurrentContextResult;
import com.java.semantic.query.application.SemanticQueryContract.EntryKind;
import com.java.semantic.query.application.SemanticQueryContract.PageRequest;
import com.java.semantic.query.application.SemanticQueryContract.RepositoryCollection;
import com.java.semantic.query.application.SemanticQueryContract.RepositoryRequest;
import com.java.semantic.query.application.SemanticQueryContract.ReviewContextResult;
import com.java.semantic.query.application.SemanticQueryContract.SelectorKind;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.GitEvidenceProperties;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

/** Native discovery/aggregate contracts; legacy source scenarios moved to source/authority suites. */
@Tag("mongo-it")
class CurrentQueryContractIT extends PublishedMongoITSupport {
    private static final Instant PUBLISHED = Instant.parse("2026-09-28T01:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static MongoDBContainer container;
    private static MongoClient client;
    private static MongoTemplate template;

    @BeforeAll
    static void startMongo() {
        container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        client = MongoClients.create(container.getConnectionString());
        template = new MongoTemplate(client, "current_context_contract");
    }
    @AfterAll
    static void stopMongo() { client.close(); container.stop(); }
    @BeforeEach
    void clearDatabase() { template.getDb().drop(); }

    @Test
    void configured_name_filter_is_literal_and_visible_paging_is_bound_to_filter_and_limit() {
        registry("alpha", "Order.Alpha", true);
        registry("ab-hidden", "Order.Hidden", true);
        registry("beta", "Order.Beta", true);
        registry("removed", "Order.Removed", false);
        registry("unrelated", "OrderXOther", true);
        ConfiguredReadPolicy policy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of("ab-hidden"), List.of(), List.of(), List.of()));
        ContextDiscoveryService discovery = discovery(template, policy);
        RepositoryRequest request = new RepositoryRequest(Optional.of("Order."), new PageRequest(Optional.empty(), 1));
        RepositoryCollection first = discovery.listRepositories(request);
        assertThat(first.items()).extracting(SemanticQueryContract.RepositoryItem::repositoryId).containsExactly("alpha");
        assertThat(first.items().getFirst().publishedRevision()).isEmpty();
        assertThat(first.page().hasMore()).isTrue();
        RepositoryCollection second = discovery.listRepositories(new RepositoryRequest(request.nameFilter(),
                new PageRequest(first.page().nextCursor(), 1)));
        assertThat(second.items()).extracting(SemanticQueryContract.RepositoryItem::repositoryId).containsExactly("beta");
        assertThat(second.page().hasMore()).isFalse();
        assertThatThrownBy(() -> discovery.listRepositories(new RepositoryRequest(Optional.of("Order"),
                new PageRequest(first.page().nextCursor(), 1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discovery.listRepositories(new RepositoryRequest(request.nameFilter(),
                new PageRequest(first.page().nextCursor(), 2)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void published_revision_and_recorded_branch_remain_separate_from_active_preparation() {
        registry("orders", "Orders", true);
        job("first-build", "BUILD", "RUNNING", true, PUBLISHED);
        CurrentContextResult before = current(discovery(template, openPolicy()), 20);
        assertThat(before.state()).isEqualTo(ContextState.UNINDEXED);
        assertThat(before.configuredBranch()).contains("main");
        assertThat(before.context()).isEmpty();
        assertThat(before.revision()).isEmpty();
        assertThat(before.activeJob()).get().extracting(SemanticQueryContract.JobIdentity::jobId).isEqualTo("first-build");
        template.getCollection("index_jobs").updateOne(new Document("jobId", "first-build"),
                new Document("$set", new Document("phase", "FAILED").append("active", false)));
        assertThat(current(discovery(template, openPolicy()), 20).state()).isEqualTo(ContextState.UNINDEXED);
        seedPublished();
        job("new-build", "BUILD", "RUNNING", true, PUBLISHED.plusSeconds(10));
        CurrentContextResult ready = current(discovery(template, openPolicy()), 20);
        assertThat(ready.state()).isEqualTo(ContextState.READY);
        assertThat(ready.revision()).contains(REVISION);
        assertThat(ready.indexedAt()).contains(PUBLISHED);
        assertThat(ready.preparationBranch()).contains("release/prepared");
        assertThat(ready.configuredBranch()).contains("main");
        assertThat(ready.activeJob()).get().extracting(SemanticQueryContract.JobIdentity::jobId).isEqualTo("new-build");
    }

    @Test
    void overview_bounds_real_modules_roots_and_packages_without_inventing_event_counts() {
        seedPublished();
        SemanticQueryContract.ContextOverview overview = current(discovery(template, openPolicy()), 1).overview().orElseThrow();
        assertThat(overview.modules().items()).extracting(SemanticQueryContract.ModuleSummary::path).containsExactly("app");
        assertThat(overview.modules().omitted()).isTrue();
        assertThat(overview.sourceRoots().items()).containsExactly("src/main/java");
        assertThat(overview.sourceRoots().omitted()).isTrue();
        assertThat(overview.packages().items()).extracting(SemanticQueryContract.PackageSummary::name).containsExactly("");
        assertThat(overview.packages().omitted()).isTrue();
        assertThat(overview.coverage().readableCode()).isEqualTo(4);
        assertThat(overview.coverage().excludedOrUnsupported()).contains(3L);
        assertThat(overview.coverage().unresolvedSemanticEvidence()).contains(7L);
        assertThat(overview.uncountedEntryKinds()).containsExactly(EntryKind.EVENT);
        assertThat(overview.entryPoints().items()).noneMatch(item -> item.kind() == EntryKind.EVENT);
    }

    @Test
    void restricted_overview_aggregates_authorized_files_and_canonical_entry_handlers() {
        seedPublished();
        seedCoverageSource(template, "src/main/java/example/public/A.java", "", scope("example.public", "A"));
        seedCoverageSource(template, "src/main/java/example/public/B.java", "JDT_SYNTAX_PROBLEM", scope("example.public", "B"));
        seedCoverageSource(template, "src/main/java/example/hidden/Secret.java", "JDT_SYNTAX_PROBLEM", scope("example.hidden", "Secret"));
        seedCoverageSource(template, "src/main/resources/RootMapper.xml", "", scope("", "RootMapper"));
        entry("example.public", "A", EntryPointKind.HTTP);
        entry("example.hidden", "Secret", EntryPointKind.HTTP);
        entry("example.public", "B", EntryPointKind.MQ);
        ConfiguredReadPolicy restricted = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(),
                List.of(new ReadPolicyProperties.PackageRule("orders", "example.hidden")), List.of(), List.of()),
                new GitEvidenceProperties(List.of("orders")));
        CurrentContextResult result = current(discovery(template, restricted), 20);
        SemanticQueryContract.ContextOverview overview = result.overview().orElseThrow();
        assertThat(overview.coverage().scope()).isEqualTo(SemanticQueryContract.CoverageScope.AUTHORIZED_SOURCE_FILES);
        assertThat(overview.coverage().readableCode()).isEqualTo(3);
        assertThat(overview.coverage().extractionIssues()).isEqualTo(1);
        assertThat(overview.coverage().excludedOrUnsupported()).isEmpty();
        assertThat(overview.coverage().unresolvedSemanticEvidence()).isEmpty();
        assertThat(overview.coverage().omittedMetrics()).containsExactlyInAnyOrder("excludedOrUnsupported", "unresolvedSemanticEvidence");
        assertThat(overview.packages().items()).extracting(SemanticQueryContract.PackageSummary::name).containsExactly("", "example.public");
        assertThat(overview.modules().items()).extracting(SemanticQueryContract.ModuleSummary::path).containsExactly("app", "mapper-module");
        assertThat(overview.sourceRoots().items()).containsExactly("src/main/java", "src/main/resources");
        assertThat(overview.entryPoints().items()).containsExactly(new SemanticQueryContract.EntryKindCount(EntryKind.HTTP, 1),
                new SemanticQueryContract.EntryKindCount(EntryKind.MQ, 1));
        assertThat(result.projectGuide()).isEmpty();
    }

    @Test
    void configured_discovery_does_not_claim_malformed_published_evidence_is_readable_or_missing_counts_are_zero() {
        seedPublished();
        ContextDiscoveryService discovery = discovery(template, openPolicy());
        template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                new Document("$unset", new Document("coverage.unresolvedSemanticEvidence", "")));
        assertThat(discovery.listRepositories(new RepositoryRequest(Optional.empty(), new PageRequest(Optional.empty(), 20))).items())
                .extracting(SemanticQueryContract.RepositoryItem::repositoryId).containsExactly("orders");
        assertThatThrownBy(() -> current(discovery, 20)).isInstanceOf(IndexContractMismatchException.class);
        ConfiguredReadPolicy forbidden = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> current(discovery(template, forbidden), 20)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void newer_attempt_shadows_ready_selection_with_review_id_tie_break_and_explicit_old_review_remains_readable() {
        SealedGeneration generation = seedPublished();
        String oldId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
        String newId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
        reviewJob("zz-old", oldId, "COMPLETE", false);
        readyRootReview(oldId, "zz-old", generation);
        ContextDiscoveryService discovery = discovery(template, openPolicy());
        ContextSelector selected = new ContextSelector(SelectorKind.COMMIT, Optional.empty(), Optional.of(REVISION), Optional.empty(), Optional.empty());
        ReviewContextResult ready = (ReviewContextResult) discovery.getContext(new ContextRequest("orders", selected, 20));
        assertThat(ready.state()).isEqualTo(ContextState.READY);
        assertThat(ready.before()).get().satisfies(before -> {
            assertThat(before.kind()).isEqualTo(SemanticQueryContract.EndpointKind.EMPTY_TREE);
            assertThat(before.context()).isEmpty();
        });
        assertThat(ready.after()).get().satisfies(after -> assertThat(after.context()).get()
                .extracting(SemanticQueryContract.ReadContext::revision).isEqualTo(REVISION));
        reviewJob("aa-new", newId, "RUNNING", true);
        ReviewContextResult preparing = (ReviewContextResult) discovery.getContext(new ContextRequest("orders", selected, 20));
        assertThat(preparing.state()).isEqualTo(ContextState.PREPARING);
        assertThat(preparing.reviewId()).contains(newId);
        assertThat(preparing.after()).isEmpty();
        assertThat(preparing.comparisonContext()).isEmpty();
        template.getCollection("index_jobs").updateOne(new Document("jobId", "aa-new"), new Document("$set",
                new Document("phase", "FAILED").append("active", false).append("failureCategory", "WORKER_INTERRUPTED")));
        ReviewContextResult failed = (ReviewContextResult) discovery.getContext(new ContextRequest("orders", selected, 20));
        assertThat(failed.state()).isEqualTo(ContextState.FAILED);
        assertThat(failed.jobId()).contains("aa-new");
        assertThat(failed.before()).isEmpty();
        ContextSelector explicit = new ContextSelector(SelectorKind.REVIEW, Optional.of(oldId), Optional.empty(), Optional.empty(), Optional.empty());
        ReviewContextResult retained = (ReviewContextResult) discovery.getContext(new ContextRequest("orders", explicit, 20));
        assertThat(retained.state()).isEqualTo(ContextState.READY);
        assertThat(retained.comparisonContext()).isEqualTo(ready.comparisonContext());
        ContextSelector absent = new ContextSelector(SelectorKind.COMMIT, Optional.empty(), Optional.of("3".repeat(40)), Optional.empty(), Optional.empty());
        ReviewContextResult missing = (ReviewContextResult) discovery.getContext(new ContextRequest("orders", absent, 20));
        assertThat(missing.state()).isEqualTo(ContextState.NOT_PREPARED);
        assertThat(missing.reviewId()).isEmpty();
    }

    @Test
    void unavailable_discovery_storage_is_not_reported_as_an_empty_repository_or_context() {
        try (MongoClient unavailable = MongoClients.create("mongodb://127.0.0.1:1/semantic?serverSelectionTimeoutMS=100&connectTimeoutMS=100")) {
            ContextDiscoveryService discovery = discovery(new MongoTemplate(unavailable, "semantic"), openPolicy());
            assertThatThrownBy(() -> current(discovery, 20)).isInstanceOf(SemanticIndexUnavailableException.class);
            assertThatThrownBy(() -> discovery.listRepositories(new RepositoryRequest(Optional.empty(), new PageRequest(Optional.empty(), 20))))
                    .isInstanceOf(SemanticIndexUnavailableException.class);
        }
    }

    @Test
    void normalizes_nested_classes_and_parameter_forms_without_overmatching_package_boundaries() {
        RepositoryId repository = new RepositoryId("orders");
        SourceTypeIdentity nested = new SourceTypeIdentity(new JavaTypeIdentity("example.api", "Outer.Inner"), "src/main/java/example/api/Outer.java");
        MethodTarget method = new MethodTarget(nested, "save", List.of("java.util.List<java.lang.String>..."));
        ConfiguredReadPolicy classPolicy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(),
                List.of(new ReadPolicyProperties.ClassRule("orders", "example.api", "Outer$Inner")), List.of()));
        ConfiguredReadPolicy methodPolicy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(),
                List.of(new ReadPolicyProperties.MethodRule("orders", "example.api", "Outer.Inner", "save", List.of("List[]")))));
        assertThat(classPolicy.isSourceVisible(repository, nested)).isFalse();
        assertThat(methodPolicy.isCodeFactVisible(repository, new CodeFactIdentity(repository, new RepositoryRevision(REVISION), CodeFactKind.METHOD, method))).isFalse();
        assertThat(policy(new ReadPolicyProperties.PackageRule("orders", "example.api")).isSourceVisible(repository,
                new SourceTypeIdentity(new JavaTypeIdentity("example.apix", "Visible"), "src/main/java/example/apix/Visible.java"))).isTrue();
    }

    private static ContextDiscoveryService discovery(MongoTemplate mongo, ConfiguredReadPolicy policy) {
        CurrentGenerationSelector currents = new CurrentGenerationSelector(mongo, policy, TIMEOUT);
        ReviewManifestReadService reviews = new ReviewManifestReadService(mongo, policy, TIMEOUT);
        ReadContextSelector contexts = new ReadContextSelector(currents, reviews, new SelectedGenerationGuard(mongo, policy, TIMEOUT), policy);
        return new ContextDiscoveryService(mongo, policy, TIMEOUT, currents, contexts, reviews);
    }
    private static CurrentContextResult current(ContextDiscoveryService discovery, int limit) {
        return (CurrentContextResult) discovery.getContext(new ContextRequest("orders",
                new ContextSelector(SelectorKind.CURRENT, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), limit));
    }
    private static ConfiguredReadPolicy openPolicy() {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()), new GitEvidenceProperties(List.of("orders")));
    }
    private static void registry(String id, String name, boolean configured) {
        template.getCollection("repositories").insertOne(new Document("repoId", id).append("displayName", name)
                .append("configured", configured).append("defaultBranch", "main"));
    }
    private static void job(String id, String operation, String phase, boolean active, Instant at) {
        template.getCollection("index_jobs").insertOne(new Document("repoId", "orders").append("jobId", id).append("operation", operation)
                .append("phase", phase).append("active", active).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)
                .append("createdAt", Date.from(at)));
    }
    private static SealedGeneration seedPublished() {
        seedCurrent(template, "orders");
        template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("configured", true)
                .append("displayName", "Orders").append("defaultBranch", "main").append("currentPointer.publishedAt", Date.from(PUBLISHED))));
        job("job-g1", "BUILD", "COMPLETE", false, PUBLISHED);
        template.getCollection("index_jobs").updateOne(new Document("jobId", "job-g1"),
                new Document("$set", new Document("preparationBranch", "release/prepared")));
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "e".repeat(64), "e".repeat(64),
                "e".repeat(64), "e".repeat(64), List.of(project("app", "src/main/java"), project("mapper-module", "src/main/resources")));
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(),
                "SUCCESS", List.of(), new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        Document structure = new Document("importedSourceRoots", List.of("src/main/java", "src/main/resources"))
                .append("packageCounts", new Document("example.public", 2L).append("example.hidden", 1L).append("", 1L))
                .append("entryPointKindCounts", new Document("HTTP", 2L).append("MQ", 1L));
        template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("analysisInputs", template.getConverter().convertToMongoType(inputs)).append("analysisFingerprint", fingerprint.digest())
                        .append("analysisEvidence", template.getConverter().convertToMongoType(evidence)).append("structure", structure)
                        .append("coverage", new Document("readableCode", 4L).append("excludedOrUnsupported", 3L)
                                .append("extractionIssues", 2L).append("unresolvedSemanticEvidence", 7L))));
        return new SealedGeneration(new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                new GenerationId("g1"), new ManifestDigest(DIGEST)), fingerprint, evidence);
    }
    private static AnalysisInputs.Project project(String path, String root) {
        return new AnalysisInputs.Project(path, "e".repeat(64), Map.of(), List.of(),
                List.of(new AnalysisInputs.Root(root, "SOURCE", true, List.of())), List.of(), List.of());
    }
    private static SourceIndexScope scope(String name, String type) {
        return new SourceIndexScope(true, List.of(name), List.of(SourceIndexScope.classKey(name, type)), List.of());
    }
    private static void entry(String packageName, String className, EntryPointKind kind) {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity(packageName, className),
                "src/main/java/" + packageName.replace('.', '/') + "/" + className + ".java");
        MethodTarget method = new MethodTarget(type, "handle", List.of());
        EntryPointTrigger trigger = kind == EntryPointKind.HTTP
                ? new EntryPointTrigger(Optional.of("GET"), Optional.of("/" + className), Optional.empty(), Optional.empty())
                : new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.of(new ExternalTarget.Destination("kafka", "orders")), Optional.empty());
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                kind == EntryPointKind.HTTP ? CodeFactKind.API_ROUTE : CodeFactKind.MQ_DESTINATION, new EntryPointIdentity(kind, method, trigger));
        EntryPointDocument entry = new EntryPointDocument(new RepositoryId("orders"), new GenerationId("g1"),
                new CodeFact(CodeFactId.from(identity), identity), kind, method, trigger,
                new SourceRange(type.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
        Document stored = new Document();
        template.getConverter().write(EntryPointPersistence.from(entry), stored);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("entryPointId", entry.fact().id().value());
        stored.put("scopePackage", "example.public");
        template.getCollection("entry_points").insertOne(stored);
    }
    private static void reviewJob(String jobId, String reviewId, String phase, boolean active) {
        job(jobId, "REVIEW", phase, active, PUBLISHED);
        template.getCollection("index_jobs").updateOne(new Document("jobId", jobId), new Document("$set", new Document("review",
                new Document("reviewId", reviewId).append("selectionKey", ReviewSelection.commit(new RepositoryRevision(REVISION)).selectionKey())
                        .append("selection", new Document("kind", "COMMIT").append("revision", REVISION)))));
    }
    private static void readyRootReview(String reviewId, String jobId, SealedGeneration generation) {
        String snapshotId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        Document snapshot = new Document(template.getCollection("git_evidence_manifests").find(new Document("evidenceId", SOURCE_SNAPSHOT)).first());
        snapshot.remove("_id"); snapshot.put("evidenceId", snapshotId); snapshot.put("scope", "REVIEW");
        snapshot.put("reviewId", reviewId); snapshot.put("ownerJobId", jobId);
        template.getCollection("git_evidence_manifests").insertOne(snapshot);
        template.getCollection("review_manifests").insertOne(new Document("repoId", "orders").append("reviewId", reviewId)
                .append("ownerJobId", jobId).append("reviewContractVersion", IndexSchemaContract.REVIEW_MANIFEST_VERSION).append("state", "READY")
                .append("selection", new Document("kind", "COMMIT").append("revision", REVISION))
                .append("resolvedEndpoints", new Document("afterRevision", REVISION).append("baselineRule", "EMPTY_TREE"))
                .append("after", new Document("generation", template.getConverter().convertToMongoType(generation)).append("snapshotId", snapshotId))
                .append("comparisonId", "cccccccc-cccc-cccc-cccc-cccccccccccc")
                .append("createdAt", Date.from(PUBLISHED)).append("publishedAt", Date.from(PUBLISHED)));
    }
}
