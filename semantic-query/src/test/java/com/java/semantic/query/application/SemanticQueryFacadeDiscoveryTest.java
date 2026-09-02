package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.EventListenerCandidate;
import com.java.semantic.model.codefact.EventListenerQuery;
import com.java.semantic.model.codefact.EventListenerResult;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.PublishedEntryPoint;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.java.semantic.query.application.SemanticQueryContract.TypeMemberRequest;
import static com.java.semantic.query.application.SemanticQueryContract.EntryPointRequest;
import static com.java.semantic.query.application.SemanticQueryContract.EventListenerRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticQueryFacadeDiscoveryTest {
    private static final String REPOSITORY = "orders";
    private static final String REVISION = "1".repeat(40);
    private static final SourceTypeIdentity TYPE = new SourceTypeIdentity(new JavaTypeIdentity("example.payment", "Payment"),
            "src/main/java/example/payment/Payment.java");

    @Test
    void lists_type_members_from_an_opaque_type_fact_id() {
        CurrentGeneration generation = generation();
        CodeFactIdentity typeIdentity = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.TYPE, TYPE);
        CodeFactDetails type = new CodeFactDetails(generation, new CodeFact(CodeFactId.from(typeIdentity), typeIdentity), range(), List.of());
        CodeFactIdentity methodIdentity = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.METHOD, new MethodTarget(TYPE, "pay", List.of()));
        CodeFactDetails method = new CodeFactDetails(generation, new CodeFact(CodeFactId.from(methodIdentity), methodIdentity), range(), List.of());
        CodeFactReadService facts = mock(CodeFactReadService.class);
        PublishedDiscoveryQueryService discovery = mock(PublishedDiscoveryQueryService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(typeIdentity))))
                .thenReturn(type);
        when(discovery.discoverTypeMembers(any())).thenReturn(new com.java.semantic.model.codefact.TypeMemberResult(generation,
                new com.java.semantic.model.codefact.TypeMemberQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), TYPE,
                        Set.of(CodeFactKind.METHOD), 0, 20),
                List.of(new com.java.semantic.model.codefact.CodeFactSummary(method.fact(), method.location())), 1, false,
                new com.java.semantic.model.index.SourceIndexCoverage(0, List.of())));

        SemanticQueryFacade facade = facade(facts, discovery);

        SemanticQueryContract.CollectionResult result = facade.listTypeMembers(new TypeMemberRequest(REPOSITORY, REVISION,
                CodeFactId.from(typeIdentity).value(), Set.of(CodeFactKind.METHOD), 0, 20));

        SemanticQueryContract.ProgramElement member = (SemanticQueryContract.ProgramElement) result.items().getFirst();
        assertEquals(CodeFactKind.METHOD, member.kind());
        assertEquals(1, result.page().total());
    }

    @Test
    void rejects_a_method_fact_id_when_listing_type_members() {
        CurrentGeneration generation = generation();
        CodeFactIdentity methodIdentity = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.METHOD, new MethodTarget(TYPE, "pay", List.of()));
        CodeFactDetails method = new CodeFactDetails(generation, new CodeFact(CodeFactId.from(methodIdentity), methodIdentity), range(), List.of());
        CodeFactReadService facts = mock(CodeFactReadService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(methodIdentity))))
                .thenReturn(method);

        SemanticQueryFacade facade = facade(facts, mock(PublishedDiscoveryQueryService.class));

        CodeFactKindMismatchException exception = assertThrows(CodeFactKindMismatchException.class, () -> facade.listTypeMembers(
                new TypeMemberRequest(REPOSITORY, REVISION, CodeFactId.from(methodIdentity).value(), Set.of(), 0, 20)));

        assertEquals("FACT_KIND_MISMATCH", exception.getMessage());
    }

    @Test
    void lists_filtered_entry_points_with_trigger_shape_and_page_metadata() {
        CurrentGeneration generation = generation();
        MethodTarget target = new MethodTarget(TYPE, "pay", List.of());
        EntryPointIdentity entryPointIdentity = new EntryPointIdentity(EntryPointKind.HTTP, target,
                new EntryPointTrigger(java.util.Optional.of("POST"), java.util.Optional.of("/payments"), java.util.Optional.empty(), java.util.Optional.empty()));
        CodeFactIdentity entryPointFact = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.API_ROUTE, entryPointIdentity);
        CodeFactIdentity methodFact = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.METHOD, target);
        CodeFactReadService facts = mock(CodeFactReadService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(entryPointFact))))
                .thenReturn(details(generation, entryPointFact));
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(methodFact))))
                .thenReturn(details(generation, methodFact));
        PublishedEntryPointQueryService entryPoints = mock(PublishedEntryPointQueryService.class);
        when(entryPoints.listEntryPoints(REPOSITORY, REVISION, Set.of(EntryPointKind.HTTP), 0, 20))
                .thenReturn(new PublishedEntryPointResult(generation, List.of(new PublishedEntryPoint(generation,
                        CodeFactId.from(entryPointFact).value(), entryPointIdentity.canonicalForm(), EntryPointKind.HTTP,
                        target.canonicalForm(), "/payments", TYPE.sourceFile())), 2, true));

        SemanticQueryFacade facade = facade(facts, mock(PublishedDiscoveryQueryService.class), entryPoints);

        SemanticQueryContract.CollectionResult result = facade.listEntryPoints(new EntryPointRequest(REPOSITORY, REVISION,
                Set.of(EntryPointKind.HTTP), 0, 20));

        SemanticQueryContract.EntryPointItem item = (SemanticQueryContract.EntryPointItem) result.items().getFirst();
        assertEquals("POST", item.trigger().method());
        assertEquals("/payments", item.trigger().value());
        assertEquals(2, result.page().total());
        assertEquals(true, result.page().hasMore());
    }

    @Test
    void preserves_the_exact_fully_qualified_event_type() {
        CurrentGeneration generation = generation();
        MethodTarget target = new MethodTarget(TYPE, "pay", List.of());
        CodeFactIdentity methodFact = new CodeFactIdentity(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION),
                CodeFactKind.METHOD, target);
        CodeFactReadService facts = mock(CodeFactReadService.class);
        when(facts.get(new CodeFactReadQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), CodeFactId.from(methodFact))))
                .thenReturn(details(generation, methodFact));
        PublishedDiscoveryQueryService discovery = mock(PublishedDiscoveryQueryService.class);
        String eventType = "com.example.payment.PaymentReceived";
        when(discovery.discoverEventListeners(any())).thenReturn(new EventListenerResult(generation,
                new EventListenerQuery(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), eventType, 0, 20),
                List.of(new EventListenerCandidate(target, range(), List.of())), 1, false));

        SemanticQueryFacade facade = facade(facts, discovery);

        SemanticQueryContract.CollectionResult result = facade.findEventListeners(new EventListenerRequest(REPOSITORY, REVISION,
                eventType, 0, 20));

        SemanticQueryContract.EventListenerItem item = (SemanticQueryContract.EventListenerItem) result.items().getFirst();
        assertEquals(eventType, item.eventType());
        assertEquals(CodeFactKind.METHOD, item.handler().kind());
    }

    private static SemanticQueryFacade facade(CodeFactReadService facts, PublishedDiscoveryQueryService discovery) {
        return facade(facts, discovery, mock(PublishedEntryPointQueryService.class));
    }

    private static SemanticQueryFacade facade(CodeFactReadService facts, PublishedDiscoveryQueryService discovery,
                                              PublishedEntryPointQueryService entryPoints) {
        PublishedSourceToolService source = mock(PublishedSourceToolService.class);
        when(source.factSource(any(CodeFactReadQuery.class), any(Integer.class))).thenReturn(new FactSourceSlice(generation(), range(), range(), "x"));
        return new SemanticQueryFacade(mock(CurrentRepositoryQueryService.class), mock(CodeFactSearchService.class), source, facts,
                discovery, entryPoints);
    }

    private static CodeFactDetails details(CurrentGeneration generation, CodeFactIdentity identity) {
        return new CodeFactDetails(generation, new CodeFact(CodeFactId.from(identity), identity), range(), List.of());
    }

    private static CurrentGeneration generation() {
        return new CurrentGeneration(new RepositoryId(REPOSITORY), new RepositoryRevision(REVISION), new GenerationId("g1"),
                new ManifestDigest("a".repeat(64)), Instant.parse("2026-09-02T00:00:00Z"));
    }

    private static SourceRange range() {
        return new SourceRange(TYPE.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));
    }
}
