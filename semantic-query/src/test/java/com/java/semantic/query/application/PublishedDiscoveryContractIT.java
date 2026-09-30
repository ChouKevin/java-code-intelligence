package com.java.semantic.query.application;

import com.java.semantic.model.codefact.AnnotationFact;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.persistence.SymbolPersistence;
import com.java.semantic.query.config.ReadPolicyProperties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import static com.java.semantic.query.application.SemanticQueryContract.*;
import static org.assertj.core.api.Assertions.*;

@Tag("mongo-it")
class PublishedDiscoveryContractIT extends PublishedMongoITSupport {
    @Test
    void type_outline_includes_direct_nested_declarations_not_descendant_members_or_other_files() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            String path = "src/main/java/example/Outer.java";
            SourceArtifactId artifact = seedSource(template, path, "class Outer { class Inner {} }\n").id();
            SourceTypeIdentity outer = new SourceTypeIdentity(new JavaTypeIdentity("example", "Outer"), path);
            SourceTypeIdentity inner = new SourceTypeIdentity(new JavaTypeIdentity("example", "Outer.Inner"), path);
            seedType(template, outer, artifact); seedType(template, inner, artifact);
            member(template, outer, artifact, "field", CodeFactKind.FIELD, 2);
            member(template, outer, artifact, "constant", CodeFactKind.ENUM_CONSTANT, 3);
            member(template, outer, artifact, "component", CodeFactKind.RECORD_COMPONENT, 4);
            CodeFactIdentity outerIdentity = new CodeFactIdentity(new com.java.semantic.model.repository.RepositoryId("orders"), new com.java.semantic.model.repository.RepositoryRevision(REVISION), CodeFactKind.TYPE, outer);
            seedSearch(template, outerIdentity, "SYMBOLS", List.of("outer"));
            seedMethod(template, methodIdentity("example", "Outer", "direct", path), List.of());
            seedMethod(template, methodIdentity("example", "Outer.Inner", "descendant", path), List.of());
            String other = "src/main/java/other/Outer.java"; seedSource(template, other, "class Outer {}\n"); seedMethod(template, methodIdentity("example", "Outer", "otherFile", other), List.of());
            SelectedSemanticQueryService service = semantic(template, policy()); ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS);
            FactCollection type = service.getOutline(context, new OutlineRequest(context.context(), new OutlineTarget(OutlineTargetKind.TYPE, Optional.of(CodeFactId.from(outerIdentity).value()), Optional.empty()), Set.of(), new PageRequest(Optional.empty(), 20)));
            assertThat(type.items()).extracting(CompactFact::displayName).containsExactly("Outer.Inner", "direct", "field", "constant", "component");
            FactCollection file = service.getOutline(context, outline(path, Optional.empty(), 20));
            assertThat(file.items()).extracting(CompactFact::displayName).containsExactlyInAnyOrder("Outer", "Outer.Inner", "direct", "descendant", "field", "constant", "component");
            assertThatThrownBy(() -> service.getOutline(context, outline("application.yml", Optional.empty(), 20))).isInstanceOf(CodeFactNotFoundException.class);
        }
    }

    @Test
    void file_outline_pages_mapper_operations_in_source_position_order_without_source_bodies() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); String path = "src/main/resources/mapper/Mapper.xml";
            SourceArtifactId artifact = seedSource(template, path, "<mapper/>\n").id();
            mapper(template, path, artifact, "selectOne", MapperStatementKind.SELECT, 1);
            mapper(template, path, artifact, "updateOne", MapperStatementKind.UPDATE, 2);
            String annotationPath = "src/main/java/example/Mapper.java";
            mapper(template, annotationPath, seedSource(template, annotationPath, "interface Mapper {}\n").id(), "annotationOne", MapperStatementKind.ANNOTATION, 3);
            template.getCollection("source_artifacts").deleteMany(new Document());
            SelectedSemanticQueryService service = semantic(template, policy()); ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.SOURCES);
            FactCollection first = service.getOutline(context, outline(path, Optional.empty(), 1));
            assertThat(first.items()).extracting(item -> item.mapperStatementKind().orElseThrow()).containsExactly(MapperStatementKind.SELECT);
            FactCollection second = service.getOutline(context, outline(path, first.page().nextCursor(), 1));
            assertThat(second.items()).extracting(item -> item.mapperStatementKind().orElseThrow()).containsExactly(MapperStatementKind.UPDATE);
            assertThat(second.page().hasMore()).isFalse();
            assertThat(service.getOutline(context, outline(annotationPath, Optional.empty(), 20)).items())
                    .extracting(item -> item.mapperStatementKind().orElseThrow()).containsExactly(MapperStatementKind.ANNOTATION);
        }
    }

    @Test
    void event_browse_filters_exact_written_type_and_excludes_canonical_forbidden_handlers() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); String path = "src/main/java/example/Listener.java"; seedSource(template, path, "class Listener {}\n");
            CodeFactIdentity visible = methodIdentity("example", "Listener", "onReady", path);
            CodeFactIdentity hidden = methodIdentity("example.privatecode", "Listener", "onHidden", path);
            List<AnnotationFact> annotations = List.of(new AnnotationFact("org.springframework.context.event.EventListener"), new AnnotationFact("org.springframework.transaction.event.TransactionalEventListener"));
            seedMethod(template, visible, annotations); seedMethod(template, hidden, annotations);
            template.getCollection("symbols").updateOne(new Document("symbolId", CodeFactId.from(hidden).value()), new Document("$set", new Document("scopePackage", "example")));
            com.java.semantic.query.config.ConfiguredReadPolicy policy = policy(new ReadPolicyProperties.PackageRule("orders", "example.privatecode"));
            SelectedSemanticQueryService service = semantic(template, policy); ReadContextSelector.AdmittedContext context = admitted(template, policy, SelectedGenerationGuard.SOURCES);
            EntryPointRequest browse = event(Optional.empty());
            EntryPointCollection result = service.listEntryPoints(context, browse);
            assertThat(result.items()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(EntryKind.EVENT); assertThat(item.factId()).isEmpty(); assertThat(item.handler().factId()).isEqualTo(CodeFactId.from(visible).value());
                assertThat(item.trigger().eventType()).contains("example.events.VideoReady");
            });
            assertThat(service.listEntryPoints(context, event(Optional.of("VideoReady"))).items()).isEmpty();
            assertThat(service.listEntryPoints(context, event(Optional.of("example.events.VideoReady"))).items()).hasSize(1);
        }
    }

    private static EntryPointRequest event(Optional<String> type) { return new EntryPointRequest(ReadContext.current("orders", REVISION), Optional.of(EntryKind.EVENT), Optional.of("on"), Optional.empty(), Optional.empty(), Optional.empty(), type, Optional.empty(), Optional.empty(), new PageRequest(Optional.empty(), 20)); }
    private static OutlineRequest outline(String path, Optional<String> cursor, int limit) { return new OutlineRequest(ReadContext.current("orders", REVISION), new OutlineTarget(OutlineTargetKind.FILE, Optional.empty(), Optional.of(path)), Set.of(), new PageRequest(cursor, limit)); }
    private static void mapper(MongoTemplate template, String path, SourceArtifactId artifact, String name, MapperStatementKind kind, int line) {
        MapperStatementIdentity mapper = new MapperStatementIdentity("example.Mapper", name, path);
        CodeFactIdentity identity = new CodeFactIdentity(new com.java.semantic.model.repository.RepositoryId("orders"), new com.java.semantic.model.repository.RepositoryRevision(REVISION), CodeFactKind.MAPPER_STATEMENT, mapper);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), new CodeFact(CodeFactId.from(identity), identity), CodeFactKind.MAPPER_STATEMENT, mapper.namespace(), name, mapper.canonicalForm(), new DeclaredType("mapper-statement"), Set.of(), List.of(), artifact, new SourceRange(path, new SyntaxRange(new SyntaxPosition(line, 0), new SyntaxPosition(line, 8))), Optional.of(kind));
        storeSymbol(template, symbol);
    }

    private static void member(MongoTemplate template, SourceTypeIdentity owner, SourceArtifactId artifact, String name, CodeFactKind kind, int line) {
        com.java.semantic.model.codefact.MemberIdentity member = new com.java.semantic.model.codefact.MemberIdentity(owner, name);
        CodeFactIdentity identity = new CodeFactIdentity(new com.java.semantic.model.repository.RepositoryId("orders"), new com.java.semantic.model.repository.RepositoryRevision(REVISION), kind, member);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), new CodeFact(CodeFactId.from(identity), identity), kind,
                owner.fullyQualifiedName(), name, member.canonicalForm(), new DeclaredType("String"), Set.of(), List.of(), artifact,
                new SourceRange(owner.sourceFile(), new SyntaxRange(new SyntaxPosition(line, 0), new SyntaxPosition(line, 8))), Optional.empty());
        storeSymbol(template, symbol);
    }

    private static void storeSymbol(MongoTemplate template, SymbolDocument symbol) {
        CodeFactIdentity identity = symbol.fact().identity();
        String path = symbol.range().sourceFile();
        Document row = new Document(); template.getConverter().write(SymbolPersistence.from(symbol), row);
        com.java.semantic.model.codefact.CodeFactScope scope = com.java.semantic.model.codefact.CodeFactScope.from(identity);
        row.put("repoId", "orders"); row.put("generationId", "g1"); row.put("symbolId", symbol.fact().id().value()); row.put("canonical", identity.canonicalForm()); row.put("sourcePath", path);
        row.put("scopePackage", scope.packageName()); row.put("scopeClass", scope.className()); row.put("scopeMethod", scope.methodName().orElse("")); row.put("scopeParameters", scope.parameterTypes()); row.put("scopePath", scope.sourcePath().orElse("")); template.getCollection("symbols").insertOne(row);
    }
}
