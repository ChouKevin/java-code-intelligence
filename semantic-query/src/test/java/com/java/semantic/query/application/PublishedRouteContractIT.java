package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import static com.java.semantic.query.application.SemanticQueryContract.*;
import static org.assertj.core.api.Assertions.*;

@Tag("mongo-it")
class PublishedRouteContractIT extends PublishedMongoITSupport {
    private static final String PATH = "src/main/java/example/Handlers.java";

    @Test
    void http_all_matching_and_typed_mq_schedule_filters_keep_real_handler_identity() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Handlers {}\n");
            ExternalTarget.Destination destination = new ExternalTarget.Destination("kafka", "orders");
            entry(template, EntryPointKind.HTTP, "get", new EntryPointTrigger(Optional.of("GET"), Optional.of("/orders"), Optional.empty(), Optional.empty()));
            entry(template, EntryPointKind.HTTP, "all", new EntryPointTrigger(Optional.of("ALL"), Optional.of("/orders"), Optional.empty(), Optional.empty()));
            entry(template, EntryPointKind.MQ, "message", new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.of(destination), Optional.empty()));
            entry(template, EntryPointKind.SCHEDULE, "scheduled", new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("0 * * * * *")));
            SelectedSemanticQueryService service = semantic(template, policy()); ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS);
            assertThat(service.listEntryPoints(context, request(EntryKind.HTTP, Optional.of(HttpMethod.GET), Optional.empty(), Optional.empty())).items()).extracting(item -> item.handler().displayName()).containsExactlyInAnyOrder("all", "get");
            assertThat(service.listEntryPoints(context, request(EntryKind.HTTP, Optional.of(HttpMethod.ALL), Optional.empty(), Optional.empty())).items()).extracting(item -> item.handler().displayName()).containsExactly("all");
            assertThat(service.listEntryPoints(context, request(EntryKind.MQ, Optional.empty(), Optional.of(destination), Optional.empty())).items()).singleElement().satisfies(item -> assertThat(item.trigger().destination()).contains(destination));
            assertThat(service.listEntryPoints(context, request(EntryKind.MQ, Optional.empty(), Optional.of(new ExternalTarget.Destination("rabbit", "orders")), Optional.empty())).items()).isEmpty();
            assertThat(service.listEntryPoints(context, request(EntryKind.SCHEDULE, Optional.empty(), Optional.empty(), Optional.of("0 * * * * *"))).items()).extracting(item -> item.handler().displayName()).containsExactly("scheduled");
            assertThat(service.listEntryPoints(context, new EntryPointRequest(context.context(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new PageRequest(Optional.empty(), 20))).items()).hasSize(4);
        }
    }

    @Test
    void missing_or_forged_handler_authority_is_not_returned_as_an_entry() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Handlers {}\n");
            entry(template, EntryPointKind.HTTP, "get", new EntryPointTrigger(Optional.of("GET"), Optional.of("/orders"), Optional.empty(), Optional.empty()));
            SelectedSemanticQueryService service = semantic(template, policy()); ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS);
            template.getCollection("entry_points").updateOne(new Document(), new Document("$set", new Document("scopeClass", "Other")));
            assertThatThrownBy(() -> service.listEntryPoints(context, request(EntryKind.HTTP, Optional.of(HttpMethod.GET), Optional.empty(), Optional.empty()))).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("entry_points").updateOne(new Document(), new Document("$set", new Document("scopeClass", "Handlers")));
            template.getCollection("symbols").deleteMany(new Document());
            assertThatThrownBy(() -> service.listEntryPoints(context, request(EntryKind.HTTP, Optional.of(HttpMethod.GET), Optional.empty(), Optional.empty()))).isInstanceOf(CodeFactNotFoundException.class);
        }
    }

    private static EntryPointRequest request(EntryKind kind, Optional<HttpMethod> method, Optional<ExternalTarget.Destination> destination, Optional<String> trigger) {
        return new EntryPointRequest(ReadContext.current("orders", REVISION), Optional.of(kind), Optional.empty(), Optional.empty(), method,
                kind == EntryKind.HTTP ? Optional.of("/orders") : Optional.empty(), Optional.empty(), destination, trigger, new PageRequest(Optional.empty(), 20));
    }
    private static void entry(MongoTemplate template, EntryPointKind kind, String name, EntryPointTrigger trigger) {
        CodeFactIdentity handler = methodIdentity("example", "Handlers", name, PATH); seedMethod(template, handler, List.of()); MethodTarget method = (MethodTarget) handler.canonicalIdentity();
        CodeFactKind factKind = switch (kind) { case HTTP -> CodeFactKind.API_ROUTE; case MQ -> CodeFactKind.MQ_DESTINATION; case SCHEDULE -> CodeFactKind.SCHEDULE; };
        CodeFactIdentity identity = new CodeFactIdentity(handler.repositoryId(), handler.repositoryRevision(), factKind, new EntryPointIdentity(kind, method, trigger));
        EntryPointDocument entry = new EntryPointDocument(identity.repositoryId(), new GenerationId("g1"), new CodeFact(CodeFactId.from(identity), identity), kind, method, trigger, new SourceRange(PATH, new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 8))));
        Document row = new Document(); template.getConverter().write(EntryPointPersistence.from(entry), row); CodeFactScope scope = CodeFactScope.from(identity);
        row.put("repoId", "orders"); row.put("generationId", "g1"); row.put("entryPointId", entry.fact().id().value()); row.put("canonical", identity.canonicalForm()); row.put("method", method.canonicalForm()); row.put("path", trigger.httpPath().orElse("")); row.put("httpMethod", trigger.httpMethod().orElse("")); row.put("sourcePath", PATH);
        row.put("scopePackage", scope.packageName()); row.put("scopeClass", scope.className()); row.put("scopeMethod", scope.methodName().orElse("")); row.put("scopeParameters", scope.parameterTypes()); row.put("scopePath", scope.sourcePath().orElse("")); template.getCollection("entry_points").insertOne(row);
    }
}
