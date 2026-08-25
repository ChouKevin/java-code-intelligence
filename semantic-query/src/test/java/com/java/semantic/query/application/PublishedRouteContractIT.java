package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedRouteContractIT extends PublishedMongoITSupport {
    @Test
    void rejects_invalid_route_input_before_pointer_or_projection_reads() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            CopyOnWriteArrayList<String> collections = new CopyOnWriteArrayList<>();
            CommandListener listener = new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName())) {
                        collections.add(event.getCommand().getString("find").getValue());
                    }
                }
            };
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(listener).build();
            try (MongoClient client = MongoClients.create(settings)) {
                MongoTemplate template = new MongoTemplate(client, "published_route_invalid");
                PublishedEntryPointQueryService service = new PublishedEntryPointQueryService(template, selector(template, policy()), Duration.ofSeconds(2));

                assertThatThrownBy(() -> service.findRoutes("orders", REVISION, "BAD METHOD", "payments"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(collections).isEmpty();
        }
    }

    @Test
    void finds_only_current_generation_http_route() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "published_route");
            seedCurrent(template, "orders");
            SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("example.api", "Payments"), "src/main/java/Payments.java");
            MethodTarget method = new MethodTarget(type, "get", java.util.List.of());
            EntryPointTrigger trigger = new EntryPointTrigger(java.util.Optional.of("GET"), java.util.Optional.of("/payments/{id}"), java.util.Optional.empty(), java.util.Optional.empty());
            CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.API_ROUTE,
                    new EntryPointIdentity(EntryPointKind.HTTP, method, trigger));
            EntryPointDocument entryPoint = new EntryPointDocument(new RepositoryId("orders"), new GenerationId("g1"),
                    new CodeFact(CodeFactId.from(identity), identity), EntryPointKind.HTTP, method, trigger,
                    new SourceRange(type.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
            Document stored = new Document();
            template.getConverter().write(EntryPointPersistence.from(entryPoint), stored);
            stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("entryPointId", entryPoint.fact().id().value());
            stored.put("canonical", identity.canonicalForm()); stored.put("method", method.canonicalForm()); stored.put("path", "/payments/{id}");
            stored.put("httpMethod", "GET"); stored.put("sourcePath", type.sourceFile()); stored.put("scopePackage", "example.api");
            stored.put("scopeClass", "Payments"); stored.put("scopeMethod", "get"); stored.put("scopeParameters", java.util.List.of()); stored.put("scopePath", type.sourceFile());
            template.getCollection("entry_points").insertOne(stored);
            CodeFactScope scope = CodeFactScope.from(identity);
            template.getCollection("search").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                    .append("factId", entryPoint.fact().id().value()).append("kind", "API_ROUTE")
                    .append("tokens", java.util.List.of("payment", "get")).append("package", scope.packageName())
                    .append("authority", "ENTRY_POINTS").append("canonical", identity.canonicalForm())
                    .append("scopePackage", scope.packageName()).append("scopeClass", scope.className())
                    .append("scopeMethod", scope.methodName().orElse("")).append("scopeParameters", scope.parameterTypes())
                    .append("scopePath", scope.sourcePath().orElse("")));
            PublishedEntryPointQueryService service = new PublishedEntryPointQueryService(template, selector(template, policy()), Duration.ofSeconds(2));

            java.util.List<com.java.semantic.model.codefact.PublishedEntryPoint> routes = service.findRoutes("orders", REVISION, "GET", "/payments/{id}");
            assertThat(routes).extracting(value -> value.factId())
                    .containsExactly(entryPoint.fact().id().value());
            assertThat(service.findRoutes("orders", REVISION, "POST", "/payments/{id}")).isEmpty();
            CodeFactSearchService search = new CodeFactSearchService(template, selector(template, policy()), Duration.ofSeconds(2));
            com.java.semantic.model.codefact.CodeFactId searchFactId = search.search(new CodeFactSearchQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), "payment"))
                    .facts().getFirst().fact().id();
            CodeFactReadService reader = new CodeFactReadService(template, selector(template, policy()), Duration.ofSeconds(2));
            assertThat(reader.get(new CodeFactReadQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    searchFactId))
                    .fact().identity()).isEqualTo(identity);
        }
    }

    @Test
    void denies_forged_route_scope_by_typed_identity_and_fails_closed_for_malformed_scope() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "published_route_scope");
            seedCurrent(template, "orders");
            EntryPointDocument hidden = entryPoint("example.private", "HiddenPayments", "hidden", "/hidden");
            storeEntryPoint(template, hidden, new CodeFactScope("example.api", "Payments", java.util.Optional.of("hidden"),
                    java.util.List.of(), java.util.Optional.of("src/main/java/HiddenPayments.java")));
            PublishedEntryPointQueryService denied = new PublishedEntryPointQueryService(template,
                    selector(template, policy(new com.java.semantic.query.config.ReadPolicyProperties.PackageRule("orders", "example.private"))),
                    Duration.ofSeconds(2));

            assertThatThrownBy(() -> denied.findRoutes("orders", REVISION, "GET", "/hidden"))
                    .isInstanceOf(RepositoryNotFoundException.class);

            EntryPointDocument malformed = entryPoint("example.api", "Payments", "malformed", "/malformed");
            storeEntryPoint(template, malformed, new CodeFactScope("example.api", "ForgedPayments", java.util.Optional.of("malformed"),
                    java.util.List.of(), java.util.Optional.of("src/main/java/Payments.java")));
            PublishedEntryPointQueryService allowed = new PublishedEntryPointQueryService(template, selector(template, policy()), Duration.ofSeconds(2));

            assertThatThrownBy(() -> allowed.findRoutes("orders", REVISION, "GET", "/malformed"))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    private static EntryPointDocument entryPoint(String packageName, String className, String methodName, String path) {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity(packageName, className),
                "src/main/java/" + className + ".java");
        MethodTarget method = new MethodTarget(type, methodName, java.util.List.of());
        EntryPointTrigger trigger = new EntryPointTrigger(java.util.Optional.of("GET"), java.util.Optional.of(path),
                java.util.Optional.empty(), java.util.Optional.empty());
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.API_ROUTE,
                new EntryPointIdentity(EntryPointKind.HTTP, method, trigger));
        return new EntryPointDocument(new RepositoryId("orders"), new GenerationId("g1"), new CodeFact(CodeFactId.from(identity), identity),
                EntryPointKind.HTTP, method, trigger, new SourceRange(type.sourceFile(),
                new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
    }

    private static void storeEntryPoint(MongoTemplate template, EntryPointDocument entryPoint, CodeFactScope flattenedScope) {
        Document stored = new Document();
        template.getConverter().write(EntryPointPersistence.from(entryPoint), stored);
        stored.put("repoId", entryPoint.repositoryId().value());
        stored.put("generationId", entryPoint.generationId().value());
        stored.put("entryPointId", entryPoint.fact().id().value());
        stored.put("canonical", entryPoint.fact().identity().canonicalForm());
        stored.put("method", entryPoint.method().canonicalForm());
        stored.put("path", entryPoint.trigger().httpPath().orElseThrow());
        stored.put("httpMethod", entryPoint.trigger().httpMethod().orElseThrow());
        stored.put("sourcePath", entryPoint.range().sourceFile());
        stored.put("scopePackage", flattenedScope.packageName());
        stored.put("scopeClass", flattenedScope.className());
        stored.put("scopeMethod", flattenedScope.methodName().orElse(""));
        stored.put("scopeParameters", flattenedScope.parameterTypes());
        stored.put("scopePath", flattenedScope.sourcePath().orElse(""));
        template.getCollection("entry_points").insertOne(stored);
    }
}
