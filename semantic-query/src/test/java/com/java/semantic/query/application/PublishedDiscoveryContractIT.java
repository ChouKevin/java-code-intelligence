package com.java.semantic.query.application;

import com.java.semantic.model.codefact.AnnotationFact;
import com.java.semantic.model.codefact.DeclarationResolutionQuery;
import com.java.semantic.model.codefact.EventListenerQuery;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mongo-it")
class PublishedDiscoveryContractIT extends PublishedMongoITSupport {
    @Test
    void derives_listener_and_declaration_only_from_current_symbols() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "published_discovery");
            seedCurrent(template, "orders");
            com.java.semantic.model.codefact.CodeFactIdentity identity = methodIdentity("example.video", "VideoListener", "onReady", "src/main/java/example/video/VideoListener.java");
            seedMethod(template, identity, List.of(new AnnotationFact("org.springframework.context.event.EventListener")));
            PublishedDiscoveryQueryService service = new PublishedDiscoveryQueryService(template, selector(template, policy()), Duration.ofSeconds(2));
            SourceTypeIdentity type = ((com.java.semantic.model.codefact.MethodTarget) identity.canonicalIdentity()).sourceType();
            seedCoverageSource(template, type.sourceFile(), "JDT_SYNTAX_PROBLEM", new SourceIndexScope(true, List.of("example.video"),
                    List.of(SourceIndexScope.classKey("example.video", "VideoListener")),
                    List.of(SourceIndexScope.methodKey("example.video", "VideoListener", "onReady", List.of("VideoReady")))));

            assertThat(service.discoverEventListeners(new EventListenerQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    "example.events.VideoReady", 0, 20)).candidates()).hasSize(1);
            assertThat(service.resolveDeclaration(new DeclarationResolutionQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    type, "onReady", Optional.empty())).declaration()).isPresent();
            assertThat(service.resolveDeclaration(new DeclarationResolutionQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    type, "localVariable", Optional.empty())).declaration()).isEmpty();
            com.java.semantic.model.codefact.TypeMemberResult methods = service.discoverTypeMembers(new TypeMemberQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), type, Set.of(CodeFactKind.METHOD), 0, 20));
            assertThat(methods.members()).extracting(member -> member.fact().identity().canonicalForm())
                    .containsExactly(identity.canonicalForm());
            assertThat(methods.coverage().indexedSourceCount()).isEqualTo(1);
            assertThat(methods.coverage().issues()).containsExactly(new com.java.semantic.model.index.SourceIndexIssue(
                    type.sourceFile(), "JDT_SYNTAX_PROBLEM"));
            seedMember(template, type, CodeFactKind.FIELD, "state", 2);
            seedMember(template, type, CodeFactKind.ENUM_CONSTANT, "READY", 3);
            seedMember(template, type, CodeFactKind.RECORD_COMPONENT, "id", 4);
            com.java.semantic.model.codefact.TypeMemberResult allMembers = service.discoverTypeMembers(new TypeMemberQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), type, TypeMemberQuery.MEMBER_KINDS, 0, 20));
            assertThat(allMembers.members()).extracting(member -> member.fact().identity().kind())
                    .containsExactly(CodeFactKind.ENUM_CONSTANT, CodeFactKind.FIELD, CodeFactKind.METHOD, CodeFactKind.RECORD_COMPONENT);
            com.java.semantic.model.codefact.TypeMemberResult page = service.discoverTypeMembers(new TypeMemberQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), type, TypeMemberQuery.MEMBER_KINDS, 1, 2));
            assertThat(page.totalCount()).isEqualTo(4);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.members()).hasSize(2);
        }
    }

    @Test
    void filters_and_pages_event_listeners_in_mongo_before_decoding() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_listener_page");
            seedCurrent(template, "orders");
            seedMethod(template, methodIdentity("example.video", "AlphaListener", "onVideo", "src/main/java/example/video/AlphaListener.java"),
                    List.of(new AnnotationFact("org.springframework.context.event.EventListener")));
            seedMethod(template, methodIdentity("example.video", "BetaListener", "onVideo", "src/main/java/example/video/BetaListener.java"),
                    List.of(new AnnotationFact("org.springframework.context.event.EventListener")));
            seedMethod(template, methodIdentity("example.video", "IgnoredListener", "onVideo", "src/main/java/example/video/IgnoredListener.java"), List.of());
            CopyOnWriteArrayList<org.bson.BsonDocument> symbolFinds = new CopyOnWriteArrayList<>();
            CommandListener listener = new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName()) && "symbols".equals(event.getCommand().getString("find").getValue())) {
                        symbolFinds.add(event.getCommand().clone());
                    }
                }
            };
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(listener).build();
            try (MongoClient client = MongoClients.create(settings)) {
                MongoTemplate observedTemplate = new MongoTemplate(client, "published_listener_page");
                PublishedDiscoveryQueryService service = new PublishedDiscoveryQueryService(observedTemplate,
                        selector(observedTemplate, policy()), Duration.ofSeconds(2));

                com.java.semantic.model.codefact.EventListenerResult result = service.discoverEventListeners(new EventListenerQuery(
                        new RepositoryId("orders"), new RepositoryRevision(REVISION), "example.events.VideoReady", 1, 1));

                assertThat(result.totalCount()).isEqualTo(2);
                assertThat(result.hasMore()).isFalse();
                assertThat(result.candidates()).extracting(candidate -> candidate.target().fullyQualifiedClassName())
                        .containsExactly("example.video.BetaListener");
            }
            assertThat(symbolFinds).singleElement().satisfies(command -> {
                assertThat(command.getDocument("filter").toJson()).contains("annotations.typeName", "parameterTypes", "example.events.VideoReady");
                assertThat(command.getInt32("skip").getValue()).isEqualTo(1);
                assertThat(command.getInt32("limit").getValue()).isEqualTo(1);
            });
        }
    }

    @Test
    void excludes_canonically_forbidden_listener_before_mongo_listener_paging() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_listener_authorization");
            seedCurrent(template, "orders");
            com.java.semantic.model.codefact.CodeFactIdentity forbidden = methodIdentity("example.private", "PrivateListener", "onVideo",
                    "src/main/java/example/private/PrivateListener.java");
            seedMethod(template, forbidden, List.of(new AnnotationFact("org.springframework.context.event.EventListener")));
            template.getCollection("symbols").updateOne(new org.bson.Document("symbolId",
                    com.java.semantic.model.codefact.CodeFactId.from(forbidden).value()), new org.bson.Document("$set",
                    new org.bson.Document("scopePackage", "example.video").append("scopeClass", "VisibleListener")
                            .append("scopeMethod", "onVideo").append("scopeParameters", List.of("example.events.VideoReady"))
                            .append("scopePath", "src/main/java/example/video/VisibleListener.java")));
            seedMethod(template, methodIdentity("example.video", "VisibleListener", "onVideo",
                    "src/main/java/example/video/VisibleListener.java"),
                    List.of(new AnnotationFact("org.springframework.context.event.EventListener")));
            ConfiguredReadPolicy deniedPolicy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(),
                    List.of(new ReadPolicyProperties.MethodRule("orders", "example.private", "PrivateListener", "onVideo",
                            List.of("example.events.VideoReady")))));
            PublishedDiscoveryQueryService service = new PublishedDiscoveryQueryService(template, selector(template, deniedPolicy), Duration.ofSeconds(2));

            com.java.semantic.model.codefact.EventListenerResult result = service.discoverEventListeners(new EventListenerQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), "example.events.VideoReady", 1, 1));

            assertThat(result.totalCount()).isEqualTo(1);
            assertThat(result.hasMore()).isFalse();
            assertThat(result.candidates()).isEmpty();
        }
    }

    private static void seedMember(MongoTemplate template, SourceTypeIdentity type, CodeFactKind kind, String name, int line) {
        com.java.semantic.model.codefact.MemberIdentity member = new com.java.semantic.model.codefact.MemberIdentity(type, name);
        com.java.semantic.model.codefact.CodeFactIdentity identity = new com.java.semantic.model.codefact.CodeFactIdentity(
                new RepositoryId("orders"), new RepositoryRevision(REVISION), kind, member);
        com.java.semantic.model.codefact.CodeFact fact = new com.java.semantic.model.codefact.CodeFact(
                com.java.semantic.model.codefact.CodeFactId.from(identity), identity);
        com.java.semantic.model.index.SymbolDocument symbol = new com.java.semantic.model.index.SymbolDocument(new RepositoryId("orders"),
                new com.java.semantic.model.index.GenerationId("g1"), fact, kind, type.fullyQualifiedName(), name,
                member.canonicalForm(), new com.java.semantic.model.codefact.DeclaredType(type.fullyQualifiedName()), Set.of(), List.of(),
                new com.java.semantic.model.index.SourceArtifactId("a".repeat(64)), new com.java.semantic.model.codefact.SourceRange(type.sourceFile(),
                new com.java.semantic.model.codefact.SyntaxRange(new com.java.semantic.model.codefact.SyntaxPosition(line, 0),
                        new com.java.semantic.model.codefact.SyntaxPosition(line, 1))));
        org.bson.Document stored = new org.bson.Document();
        template.getConverter().write(symbol, stored);
        com.java.semantic.model.codefact.CodeFactScope scope = com.java.semantic.model.codefact.CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value());
        stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", type.sourceFile());
        stored.put("scopePackage", scope.packageName()); stored.put("scopeClass", scope.className());
        stored.put("scopeMethod", ""); stored.put("scopeParameters", List.of()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
    }
}
