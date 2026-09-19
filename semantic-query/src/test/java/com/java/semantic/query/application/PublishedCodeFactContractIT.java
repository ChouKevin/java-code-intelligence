package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedCodeFactContractIT extends PublishedMongoITSupport {

    private static PublishedMongoLifecycle lifecycle;

    @BeforeAll
    static void startMongo() {
        lifecycle = PublishedMongoLifecycle.start();
    }

    @AfterAll
    static void stopMongo() {
        lifecycle.close();
    }

    @Test
    void method_search_and_get_ignore_unrelated_incompatible_projections_but_require_symbols() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment",
                    "src/main/java/example/payment/PaymentService.java");
            seedMethod(template, identity, List.of());
            seedSearch(template, identity, "SYMBOLS", List.of("find", "payment"));
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("projectionVersions", List.of(
                            new Document("name", "SOURCES").append("version", 1),
                            new Document("name", "SYMBOLS").append("version", 2),
                            new Document("name", "RELATIONS").append("version", 1),
                            new Document("name", "ENTRY_POINTS").append("version", 1),
                            new Document("name", "SEARCH").append("version", 2)))));
            CurrentGenerationSelector selector = selector(template, policy());
            SelectedGeneration context = selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(java.util.Set.of(CodeFactKind.METHOD)));
            SelectedGenerationGuard guard = guard(template, policy());
            CodeFactSearchService search = new CodeFactSearchService(template, guard, Duration.ofSeconds(2));
            CodeFactReadService reader = new CodeFactReadService(template, guard, Duration.ofSeconds(2));
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), "findPayment", java.util.Set.of(CodeFactKind.METHOD), Optional.empty(), 0, 20);
            CodeFactSearchResult results = search.search(context, query);

            assertThat(results.facts()).extracting(summary -> summary.fact().identity()).containsExactly(identity);
            assertThat(reader.get(context, new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    CodeFactId.from(identity))).fact().identity()).isEqualTo(identity);

            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("projectionVersions", List.of(
                            new Document("name", "SOURCES").append("version", 1),
                            new Document("name", "SYMBOLS").append("version", 1),
                            new Document("name", "RELATIONS").append("version", 1),
                            new Document("name", "ENTRY_POINTS").append("version", 1),
                            new Document("name", "SEARCH").append("version", 2)))));

            assertThatThrownBy(() -> search.search(context, query))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }
    @Test
    void searches_authorized_derived_rows_and_rejects_denied_package_scope() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment", "src/main/java/example/payment/PaymentService.java");
            seedMethod(template, identity, java.util.List.of());
            CodeFactScope scope = CodeFactScope.from(identity);
            template.getCollection("search").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                    .append("factId", com.java.semantic.model.codefact.CodeFactId.from(identity).value()).append("kind", "METHOD")
                    .append("tokens", java.util.List.of("find", "payment", "findpayment")).append("package", "example.payment")
                    .append("authority", "SYMBOLS").append("canonical", identity.canonicalForm()).append("scopePackage", scope.packageName())
                    .append("scopeClass", scope.className()).append("scopeMethod", scope.methodName().orElse(""))
                    .append("scopeParameters", scope.parameterTypes()).append("scopePath", scope.sourcePath().orElse("")));
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), "findPayment");
            CurrentGenerationSelector selector = selector(template, policy());
            SelectedGeneration context = selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(query.kinds()));
            CodeFactSearchService visible = new CodeFactSearchService(template, guard(template, policy()), Duration.ofSeconds(2));

            CodeFactSearchResult result = visible.search(context, query);
            assertThat(result.facts()).hasSize(1);
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            CodeFactReadQuery readQuery = new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    result.facts().getFirst().fact().id());
            assertThat(reader.get(context, readQuery).fact().identity()).isEqualTo(identity);
            assertThatThrownBy(() -> reader.get(context, new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    new CodeFactId("0".repeat(64))))).isInstanceOf(CodeFactNotFoundException.class);
            CodeFactSearchService denied = new CodeFactSearchService(template, guard(template,
                    policy(new ReadPolicyProperties.PackageRule("orders", "example.payment"))), Duration.ofSeconds(2));
            assertThat(denied.search(context, query).facts()).isEmpty();
            CodeFactSearchQuery deniedQuery = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    "findPayment", java.util.Set.of(), java.util.Optional.of("example.payment"), 0, 20);
            assertThatThrownBy(() -> denied.search(context, deniedQuery))
                    .isInstanceOf(RepositoryNotFoundException.class);
            assertThatThrownBy(() -> new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    "findPayment", java.util.Set.of(), Optional.of("example..payment"), 0, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void resolves_relation_search_rows_through_their_relation_authority() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity from = methodIdentity("example.payment", "PaymentService", "charge", "src/main/java/example/payment/PaymentService.java");
            SourceRange range = new SourceRange("src/main/java/example/payment/PaymentService.java",
                    new SyntaxRange(new SyntaxPosition(2, 0), new SyntaxPosition(2, 10)));
            RelationTarget target = new RelationTarget.External(new ExternalTarget.Endpoint("POST", "https://payments.example/charge"));
            RelationIdentity relationIdentity = new RelationIdentity(from, RelationKind.CALLS_OUTBOUND_API, target, range);
            CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    CodeFactKind.OUTBOUND_API, relationIdentity);
            RelationDocument relation = new RelationDocument(new RepositoryId("orders"), new GenerationId("g1"),
                    new CodeFact(CodeFactId.from(identity), identity), RelationKind.CALLS_OUTBOUND_API, from, target,
                    new SourceArtifactId("a".repeat(64)), range);
            Document stored = new Document();
            template.getConverter().write(relation, stored);
            stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("relationId", relation.fact().id().value());
            stored.put("canonical", identity.canonicalForm()); stored.put("from", from.canonicalForm());
            stored.put("target", target.canonicalForm()); stored.put("kind", RelationKind.CALLS_OUTBOUND_API.name());
            stored.put("sourcePath", range.sourceFile());
            template.getCollection("relations").insertOne(stored);
            seedSearch(template, identity, "RELATIONS", List.of("charge", "payment"));
            CurrentGenerationSelector selector = selector(template, policy());
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), "charge");
            SelectedGeneration context = selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(java.util.Set.of(CodeFactKind.OUTBOUND_API)));
            CodeFactSearchService service = new CodeFactSearchService(template, guard(template, policy()), Duration.ofSeconds(2));

            CodeFactSearchResult result = service.search(context, query);
            assertThat(result.facts()).extracting(summary -> summary.fact().identity()).containsExactly(identity);
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            assertThat(reader.get(context, new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    result.facts().getFirst().fact().id())).fact().identity()).isEqualTo(identity);
        }
    }

    @Test
    void returns_only_code_proven_fee_declaration_evidence() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentFeeCalculator", "feeFormula",
                    "src/main/java/example/payment/PaymentFeeCalculator.java");
            seedMethod(template, identity, List.of());
            seedSearch(template, identity, "SYMBOLS", List.of("feeFormula", "fee"));
            CurrentGenerationSelector selector = selector(template, policy());
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), "feeFormula");
            SelectedGeneration context = selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(java.util.Set.of(CodeFactKind.METHOD)));
            CodeFactSearchService search = new CodeFactSearchService(template, guard(template, policy()), Duration.ofSeconds(2));
            CodeFactId factId = search.search(context, query).facts().getFirst().fact().id();
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            com.java.semantic.model.codefact.CodeFactDetails details = reader.get(context, new CodeFactReadQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), factId));

            assertThat(details.fact().identity()).isEqualTo(identity);
            assertThat(details.location().sourceFile()).isEqualTo("src/main/java/example/payment/PaymentFeeCalculator.java");
            assertThat(details.annotations()).isEmpty();
            assertThat(com.java.semantic.model.codefact.CodeFactDetails.class.getRecordComponents())
                    .extracting(component -> component.getName()).containsExactly("generation", "fact", "location", "annotations");
        }
    }

    @Test
    void unfiltered_search_requires_all_possible_authorities_while_exact_read_requires_only_its_authority() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment",
                    "src/main/java/example/payment/PaymentService.java");
            seedMethod(template, identity, List.of());
            seedSearch(template, identity, "SYMBOLS", List.of("find", "payment"));
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("projectionVersions", List.of(
                            new Document("name", "SOURCES").append("version", 2),
                            new Document("name", "SYMBOLS").append("version", 2),
                            new Document("name", "RELATIONS").append("version", 1),
                            new Document("name", "SEARCH").append("version", 2)))));
            CurrentGenerationSelector selector = selector(template, policy());
            SelectedGeneration selectedContext = selector.selectCodeFact("orders", REVISION, identity,
                    CodeFactReadService.requirementsForSearchKinds(java.util.Set.of(identity.kind())));
            CodeFactSearchService search = new CodeFactSearchService(template, guard(template, policy()), Duration.ofSeconds(2));
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), "findPayment");

            assertThatThrownBy(() -> search.search(selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(query.kinds())), query))
                    .isInstanceOf(IndexContractMismatchException.class);
            assertThat(reader.get(selectedContext, new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    CodeFactId.from(identity))).fact().identity()).isEqualTo(identity);
        }
    }

    @Test
    void reports_only_pre_authorized_current_source_coverage_and_syntax_issues() {
        try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment",
                    "src/main/java/example/payment/PaymentService.java");
            seedMethod(template, identity, List.of());
            seedSearch(template, identity, "SYMBOLS", List.of("find", "payment", "findpayment"));
            seedCoverageSource(template, "src/main/java/example/payment/PaymentService.java", "JDT_SYNTAX_PROBLEM",
                    new SourceIndexScope(true, List.of("example.payment"),
                            List.of(SourceIndexScope.classKey("example.payment", "PaymentService")),
                            List.of(SourceIndexScope.methodKey("example.payment", "PaymentService", "findPayment",
                                    List.of("example.events.VideoReady")))));
            seedCoverageSource(template, "src/main/java/example/payment/InternalPaymentService.java", "SECRET_SYNTAX",
                    new SourceIndexScope(true, List.of("example.payment"),
                            List.of(SourceIndexScope.classKey("example.payment", "InternalPaymentService")), List.of()));
            seedCoverageSource(template, "src/main/java/example/video/VideoService.java", "",
                    new SourceIndexScope(true, List.of("example.video"),
                            List.of(SourceIndexScope.classKey("example.video", "VideoService")), List.of()));
            seedCoverageSource(template, "src/main/java/example/unknown/Unknown.java", "UNKNOWN_SCOPE",
                    new SourceIndexScope(false, List.of(), List.of(), List.of()));
            ConfiguredReadPolicy policy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(),
                    List.of(new ReadPolicyProperties.ClassRule("orders", "example.payment", "InternalPaymentService")), List.of()));
            CopyOnWriteArrayList<org.bson.BsonDocument> generationFileFilters = new CopyOnWriteArrayList<>();
            CommandListener listener = new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if (("find".equals(event.getCommandName()) || "count".equals(event.getCommandName()))
                            && "generation_files".equals(event.getCommand().getString(event.getCommandName()).getValue())) {
                        org.bson.BsonDocument filter = "find".equals(event.getCommandName())
                                ? event.getCommand().getDocument("filter") : event.getCommand().getDocument("query");
                        generationFileFilters.add(filter.clone());
                    }
                }
            };
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(lifecycle.connectionString()))
                    .addCommandListener(listener).build();
            try (MongoClient observedClient = MongoClients.create(settings)) {
                MongoTemplate observedTemplate = new MongoTemplate(observedClient, invocation.databaseName());
                CurrentGenerationSelector selector = selector(observedTemplate, policy);
                SelectedGeneration context = selector.select("orders", REVISION,
                        CodeFactReadService.requirementsForSearchKinds(java.util.Set.of()));
                CodeFactSearchService service = new CodeFactSearchService(observedTemplate, guard(observedTemplate, policy), Duration.ofSeconds(2));

                CodeFactSearchQuery broadQuery = new CodeFactSearchQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), "findPayment");
                CodeFactSearchQuery scopedQuery = new CodeFactSearchQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), "findPayment", java.util.Set.of(), Optional.of("example.payment"), 0, 20);
                CodeFactSearchResult broad = service.search(context, broadQuery);
                CodeFactSearchResult result = service.search(context, scopedQuery);

                assertThat(broad.coverage().indexedSourceCount()).isEqualTo(2);
                assertThat(result.coverage().indexedSourceCount()).isEqualTo(1);
                assertThat(result.coverage().issues()).containsExactly(new com.java.semantic.model.index.SourceIndexIssue(
                    "src/main/java/example/payment/PaymentService.java", "JDT_SYNTAX_PROBLEM"));
                SemanticQueryContract.SearchCodeResult publicResult = SemanticResultMapper.toSearchCodeResult(result,
                        List.of(new SemanticQueryContract.ProgramElement(CodeFactId.from(identity).value(), CodeFactKind.METHOD,
                                identity.canonicalIdentity().canonicalForm(), new SemanticQueryContract.SourceSnippet(
                                "src/main/java/example/payment/PaymentService.java", 1, 1, "findPayment"))));
                assertThat(publicResult.sourceCoverage()).isEqualTo(new SemanticQueryContract.SourceCoverage(1, 1,
                        List.of("JDT_SYNTAX_PROBLEM")));
            }
            assertThat(generationFileFilters).hasSize(2).allSatisfy(filter -> {
                assertThat(filter.toJson()).contains("scopeUsable", "scopeClassKeys", "InternalPaymentService");
            });
        }
    }

    private static void seedSearch(MongoTemplate template, CodeFactIdentity identity, String authority, List<String> tokens) {
        CodeFactScope scope = CodeFactScope.from(identity);
        template.getCollection("search").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("factId", CodeFactId.from(identity).value()).append("kind", identity.kind().name())
                .append("tokens", tokens).append("package", scope.packageName()).append("authority", authority)
                .append("canonical", identity.canonicalForm()).append("scopePackage", scope.packageName())
                .append("scopeClass", scope.className()).append("scopeMethod", scope.methodName().orElse(""))
                .append("scopeParameters", scope.parameterTypes()).append("scopePath", scope.sourcePath().orElse("")));
    }
}
