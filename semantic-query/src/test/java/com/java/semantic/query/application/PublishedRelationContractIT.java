package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.RelationDocument;
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
class PublishedRelationContractIT extends PublishedMongoITSupport {
    private static final String PATH = "src/main/java/example/Service.java";

    @Test
    void callers_fill_sparse_authorized_pages_and_preserve_distinct_occurrences() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Service {}\n");
            CodeFactIdentity target = methodIdentity("example", "Service", "target", PATH);
            CodeFactIdentity visible = methodIdentity("example", "Service", "caller", PATH);
            CodeFactIdentity denied = methodIdentity("example.privatecode", "Hidden", "caller", PATH);
            for (CodeFactIdentity identity : List.of(target, visible, denied)) seedMethod(template, identity, List.of());
            seedSearch(template, target, "SYMBOLS", List.of("target"));
            for (int line = 0; line < 130; line++) seedRelation(template, denied, RelationKind.CALLS, new RelationTarget.Internal(target), range(line));
            for (int line = 200; line < 203; line++) seedRelation(template, visible, RelationKind.CALLS, new RelationTarget.Internal(target), range(line));
            com.java.semantic.query.config.ConfiguredReadPolicy policy = policy(new ReadPolicyProperties.PackageRule("orders", "example.privatecode"));
            SelectedSemanticQueryService service = semantic(template, policy);
            ReadContextSelector.AdmittedContext context = admitted(template, policy, SelectedGenerationGuard.ALL_PROJECTIONS);
            RelationCollection first = service.findRelations(context, request(target, RelationMode.CALLERS, Optional.empty(), 2));
            assertThat(first.items()).hasSize(2).allSatisfy(item -> {
                assertThat(item.origin().displayName()).isEqualTo("caller");
                assertThat(item.target().resolution()).isEqualTo(TargetResolution.INTERNAL);
                assertThat(item.occurrence().path()).isEqualTo(PATH);
            });
            RelationCollection second = service.findRelations(context, request(target, RelationMode.CALLERS, first.page().nextCursor(), 2));
            assertThat(second.items()).hasSize(1);
            assertThat(second.page().hasMore()).isFalse();
            assertThat(first.items().stream().map(item -> item.occurrence().factId()).toList()).doesNotContain(second.items().getFirst().occurrence().factId());
            assertThatThrownBy(() -> service.findRelations(context, request(target, RelationMode.CALLEES, first.page().nextCursor(), 2))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void callees_keep_external_unresolved_and_internal_targets_distinct_and_missing_authority_fails() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Service {}\n");
            CodeFactIdentity caller = methodIdentity("example", "Service", "caller", PATH);
            CodeFactIdentity target = methodIdentity("example", "Service", "target", PATH);
            seedMethod(template, caller, List.of()); seedMethod(template, target, List.of()); seedSearch(template, caller, "SYMBOLS", List.of("caller"));
            seedRelation(template, caller, RelationKind.CALLS, new RelationTarget.Internal(target), range(1));
            seedRelation(template, caller, RelationKind.CALLS, new RelationTarget.External(new ExternalTarget.NominalType(new com.java.semantic.model.codefact.DeclaredType("external.Client"))), range(2));
            RelationDocument unresolved = seedRelation(template, caller, RelationKind.CALLS,
                    new RelationTarget.External(new ExternalTarget.UnresolvedCall(
                            "client.charge(() -> { inlineBodyOnlyInSource(); })", "client", "charge", 1)), range(3));
            seedSearch(template, unresolved.fact().identity(), "RELATIONS", List.of("charge"));
            SelectedSemanticQueryService service = semantic(template, policy()); ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS);
            RelationCollection result = service.findRelations(context, request(caller, RelationMode.CALLEES, Optional.empty(), 20));
            assertThat(result.items()).extracting(item -> item.target().resolution()).containsExactlyInAnyOrder(TargetResolution.INTERNAL, TargetResolution.EXTERNAL, TargetResolution.UNRESOLVED);
            assertThat(result.toString()).doesNotContain("inlineBodyOnlyInSource");
            ExternalTargetInfo unresolvedTarget = result.items().stream()
                    .filter(item -> item.target().resolution() == TargetResolution.UNRESOLVED)
                    .findFirst().orElseThrow().target().external().orElseThrow();
            assertThat(unresolvedTarget.displayName()).isEqualTo("charge");
            assertThat(unresolvedTarget.arity()).contains(1);
            FactCollection search = service.searchCode(context, new SearchCodeRequest(context.context(), "charge",
                    Set.of(unresolved.fact().identity().kind()), Optional.empty(), Optional.empty(),
                    new PageRequest(Optional.empty(), 20)));
            assertThat(search.toString()).doesNotContain("inlineBodyOnlyInSource");
            assertThat(search.items()).singleElement().satisfies(item -> {
                assertThat(item.factId()).isEqualTo(unresolved.fact().id().value());
                assertThat(item.displayName()).isEqualTo("charge");
            });
            template.getCollection("symbols").deleteOne(new Document("symbolId", CodeFactId.from(target).value()));
            assertThatThrownBy(() -> service.findRelations(context, request(caller, RelationMode.CALLEES, Optional.empty(), 20))).isInstanceOf(CodeFactNotFoundException.class);
        }
    }

    @Test
    void forged_flattened_origin_cannot_convert_a_relation_into_other_evidence() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Service {}\n");
            CodeFactIdentity target = methodIdentity("example", "Service", "target", PATH); seedMethod(template, target, List.of()); seedSearch(template, target, "SYMBOLS", List.of("target"));
            seedRelation(template, target, RelationKind.CALLS, new RelationTarget.Internal(target), range(1));
            template.getCollection("relations").updateOne(new Document(), new Document("$set", new Document("from", "forged")));
            assertThatThrownBy(() -> semantic(template, policy()).findRelations(admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS), request(target, RelationMode.CALLERS, Optional.empty(), 20))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void implementations_accept_type_and_method_and_references_disclose_projected_occurrences() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            com.java.semantic.model.index.SourceArtifactId artifact = seedSource(template, PATH, "class Service {}\n").id();
            com.java.semantic.model.codefact.SourceTypeIdentity api = new com.java.semantic.model.codefact.SourceTypeIdentity(
                    new com.java.semantic.model.codefact.JavaTypeIdentity("example", "Api"), PATH);
            com.java.semantic.model.codefact.SourceTypeIdentity implementation = new com.java.semantic.model.codefact.SourceTypeIdentity(
                    new com.java.semantic.model.codefact.JavaTypeIdentity("example", "Service"), PATH);
            seedType(template, api, artifact); seedType(template, implementation, artifact);
            CodeFactIdentity targetType = new CodeFactIdentity(new com.java.semantic.model.repository.RepositoryId("orders"),
                    new com.java.semantic.model.repository.RepositoryRevision(REVISION), com.java.semantic.model.codefact.CodeFactKind.TYPE, api);
            CodeFactIdentity fromType = new CodeFactIdentity(targetType.repositoryId(), targetType.repositoryRevision(), targetType.kind(), implementation);
            CodeFactIdentity targetMethod = methodIdentity("example", "Api", "execute", PATH);
            CodeFactIdentity fromMethod = methodIdentity("example", "Service", "execute", PATH);
            seedMethod(template, targetMethod, List.of()); seedMethod(template, fromMethod, List.of());
            seedSearch(template, targetType, "SYMBOLS", List.of("api")); seedSearch(template, targetMethod, "SYMBOLS", List.of("execute"));
            seedRelation(template, fromType, RelationKind.IMPLEMENTS, new RelationTarget.Internal(targetType), range(1));
            seedRelation(template, fromMethod, RelationKind.OVERRIDES, new RelationTarget.Internal(targetMethod), range(2));
            seedRelation(template, fromMethod, RelationKind.REFERENCES, new RelationTarget.Internal(targetType), range(3));
            SelectedSemanticQueryService service = semantic(template, policy());
            ReadContextSelector.AdmittedContext context = admitted(template, policy(), SelectedGenerationGuard.ALL_PROJECTIONS);
            assertThat(service.findRelations(context, request(targetType, RelationMode.IMPLEMENTATIONS, Optional.empty(), 20)).items())
                    .singleElement().satisfies(item -> { assertThat(item.relationKind()).isEqualTo(RelationKind.IMPLEMENTS); assertThat(item.occurrence().range().start().line()).isEqualTo(1); });
            assertThat(service.findRelations(context, request(targetMethod, RelationMode.IMPLEMENTATIONS, Optional.empty(), 20)).items())
                    .singleElement().satisfies(item -> assertThat(item.relationKind()).isEqualTo(RelationKind.OVERRIDES));
            RelationCollection references = service.findRelations(context, request(targetType, RelationMode.REFERENCES, Optional.empty(), 20));
            assertThat(references.evidenceScope()).isEqualTo(RelationEvidenceScope.PROJECTED_REFERENCES);
            assertThat(references.items()).singleElement().satisfies(item -> assertThat(item.occurrence().range().start().line()).isEqualTo(3));
            assertThatThrownBy(() -> service.findRelations(context, request(targetType, RelationMode.CALLERS, Optional.empty(), 20))).isInstanceOf(CodeFactKindMismatchException.class);
        }
    }

    private static SourceRange range(int line) { return new SourceRange(PATH, new SyntaxRange(new SyntaxPosition(line, 0), new SyntaxPosition(line, 1))); }
    private static RelationRequest request(CodeFactIdentity target, RelationMode mode, Optional<String> cursor, int limit) {
        return new RelationRequest(ReadContext.current("orders", REVISION), mode, CodeFactId.from(target).value(), new PageRequest(cursor, limit));
    }
}
