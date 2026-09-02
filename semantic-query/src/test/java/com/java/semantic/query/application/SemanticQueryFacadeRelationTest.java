package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.query.PublishedRelationPage;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.java.semantic.query.application.SemanticQueryContract.RelationRequest;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticQueryFacadeRelationTest {
    private static final String REPOSITORY = "orders";
    private static final String REVISION = "1".repeat(40);

    @Test
    void callers_are_one_hop_and_report_the_exact_call_site() {
        CodeFactIdentity target = methodIdentity();
        CodeFactIdentity caller = methodIdentity("Caller", "call");
        RelationDocument relation = relation(caller, RelationKind.CALLS, new RelationTarget.Internal(target));
        CodeFactReadService facts = mock(CodeFactReadService.class);
        PublishedRelationQueryService relations = mock(PublishedRelationQueryService.class);
        PublishedSourceToolService source = mock(PublishedSourceToolService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(target))))
                .thenReturn(details(target));
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(caller))))
                .thenReturn(details(caller));
        when(relations.findCallers(any(PublishedRelationQuery.class))).thenReturn(result(target, List.of(relation)));
        when(source.factSource(any(CodeFactReadQuery.class), anyInt())).thenReturn(slice());

        SemanticQueryFacade facade = facade(facts, source, relations);

        SemanticQueryContract.CollectionResult result = facade.findCallers(new RelationRequest(REPOSITORY, REVISION,
                CodeFactId.from(target).value(), 0, 20));

        assertEquals(1, result.items().size());
        SemanticQueryContract.CallerItem callerItem = (SemanticQueryContract.CallerItem) result.items().getFirst();
        assertEquals(CodeFactId.from(caller).value(), callerItem.caller().factId());
        assertEquals(relation.fact().id().value(), callerItem.callSite().factId());
        assertEquals("service.charge(request)", callerItem.callSite().source().code());
    }

    @Test
    void references_reject_occurrence_facts_but_accept_symbol_declarations_without_relations() {
        CodeFactIdentity occurrence = occurrenceIdentity();
        CodeFactIdentity field = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactKind.FIELD,
                new com.java.semantic.model.codefact.MemberIdentity(type(), "state"));
        CodeFactReadService facts = mock(CodeFactReadService.class);
        PublishedRelationQueryService relations = mock(PublishedRelationQueryService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(occurrence))))
                .thenReturn(details(occurrence));
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(field))))
                .thenReturn(details(field));
        when(relations.findReferences(any(PublishedRelationQuery.class))).thenReturn(result(field, List.of()));
        SemanticQueryFacade facade = facade(facts, mock(PublishedSourceToolService.class), relations);

        assertThrows(CodeFactKindUnsupportedException.class, () -> facade.findReferences(new RelationRequest(REPOSITORY, REVISION,
                CodeFactId.from(occurrence).value(), 0, 20)));
        SemanticQueryContract.CollectionResult result = assertDoesNotThrow(() -> facade.findReferences(new RelationRequest(REPOSITORY,
                REVISION, CodeFactId.from(field).value(), 0, 20)));
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.page().total());
    }

    @Test
    void external_callees_expose_display_identity_without_a_fabricated_fact_id() {
        CodeFactIdentity target = methodIdentity();
        RelationTarget.External external = new RelationTarget.External(new ExternalTarget.UnresolvedCall("client.charge(request)", "client", "charge", 1));
        RelationDocument relation = relation(target, RelationKind.CALLS, external);
        CodeFactReadService facts = mock(CodeFactReadService.class);
        PublishedRelationQueryService relations = mock(PublishedRelationQueryService.class);
        PublishedSourceToolService source = mock(PublishedSourceToolService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(target))))
                .thenReturn(details(target));
        when(relations.findCallees(any(PublishedRelationQuery.class))).thenReturn(result(target, List.of(relation)));
        when(source.factSource(any(CodeFactReadQuery.class), anyInt())).thenReturn(slice());

        SemanticQueryContract.CollectionResult result = facade(facts, source, relations).findCallees(new RelationRequest(REPOSITORY,
                REVISION, CodeFactId.from(target).value(), 0, 20));

        SemanticQueryContract.CalleeItem callee = (SemanticQueryContract.CalleeItem) result.items().getFirst();
        assertEquals("client.charge(request)|client|charge|1", callee.callee().displayName());
        assertTrue(Objects.isNull(callee.callee().factId()));
    }

    @Test
    void accepted_kind_sets_are_exact_and_reject_every_other_kind() {
        assertEquals(Set.of(CodeFactKind.METHOD), FactKindPolicy.METHOD_IMPLEMENTATIONS);
        assertEquals(Set.of(CodeFactKind.TYPE), FactKindPolicy.TYPE_MEMBERS);
        assertEquals(Set.of(CodeFactKind.METHOD), FactKindPolicy.CALLERS);
        assertEquals(Set.of(CodeFactKind.METHOD), FactKindPolicy.CALLEES);
        assertEquals(Set.of(CodeFactKind.TYPE, CodeFactKind.METHOD, CodeFactKind.FIELD, CodeFactKind.ENUM_CONSTANT,
                CodeFactKind.RECORD_COMPONENT, CodeFactKind.MAPPER_STATEMENT), FactKindPolicy.REFERENCE_TARGETS);
        assertThrows(CodeFactKindUnsupportedException.class, () -> FactKindPolicy.require(details(occurrenceIdentity()),
                FactKindPolicy.REFERENCE_TARGETS));
    }

    private static CodeFactIdentity methodIdentity() {
        return methodIdentity("OrderService", "place");
    }

    private static CodeFactIdentity methodIdentity(String className, String methodName) {
        return new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactKind.METHOD,
                new MethodTarget(type(className), methodName, List.of()));
    }

    private static SourceTypeIdentity type() {
        return type("OrderService");
    }

    private static SourceTypeIdentity type(String className) {
        return new SourceTypeIdentity(new JavaTypeIdentity("example.orders", className),
                "src/main/java/example/orders/" + className + ".java");
    }

    private static CurrentGeneration generation() {
        return new CurrentGeneration(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), new GenerationId("g1"),
                new ManifestDigest("a".repeat(64)), Instant.parse("2026-09-02T00:00:00Z"));
    }

    private static SourceRange range() {
        return new SourceRange("src/main/java/example/orders/OrderService.java",
                new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 23)));
    }

    private static SemanticQueryFacade facade(CodeFactReadService facts, PublishedSourceToolService source,
                                              PublishedRelationQueryService relations) {
        return new SemanticQueryFacade(mock(CurrentRepositoryQueryService.class), mock(CodeFactSearchService.class), source, facts,
                mock(PublishedDiscoveryQueryService.class), mock(PublishedEntryPointQueryService.class), relations);
    }

    private static CodeFactDetails details(CodeFactIdentity identity) {
        return new CodeFactDetails(generation(), new CodeFact(CodeFactId.from(identity), identity), range(), List.of());
    }

    private static RelationDocument relation(CodeFactIdentity from, RelationKind kind, RelationTarget target) {
        RelationIdentity identity = new RelationIdentity(from, kind, target, range());
        CodeFactIdentity factIdentity = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.TYPE_USAGE, identity);
        return new RelationDocument(new RepositoryId(REPOSITORY), new GenerationId("g1"), new CodeFact(CodeFactId.from(factIdentity), factIdentity),
                kind, from, target, new SourceArtifactId("a".repeat(64)), range());
    }

    private static PublishedRelationResult result(CodeFactIdentity target, List<RelationDocument> relations) {
        return new PublishedRelationResult(generation(), target, relations,
                new PublishedRelationPage(0, 20, relations.size(), relations.size()));
    }

    private static FactSourceSlice slice() {
        return new FactSourceSlice(generation(), range(), range(), "service.charge(request)");
    }

    private static CodeFactIdentity occurrenceIdentity() {
        CodeFactIdentity from = methodIdentity();
        RelationIdentity identity = new RelationIdentity(from, RelationKind.REFERENCES, new RelationTarget.Internal(from), range());
        return new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactKind.TYPE_USAGE, identity);
    }
}
