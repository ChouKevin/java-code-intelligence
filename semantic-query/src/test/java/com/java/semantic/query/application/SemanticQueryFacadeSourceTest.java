package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.java.semantic.query.application.SemanticQueryContract.FactRange;
import static com.java.semantic.query.application.SemanticQueryContract.FactSourceRequest;
import static com.java.semantic.query.application.SemanticQueryContract.FactSourceResult;
import static com.java.semantic.query.application.SemanticQueryContract.PageRequest;
import static com.java.semantic.query.application.SemanticQueryContract.RepositoryItem;
import static com.java.semantic.query.application.SemanticQueryContract.RepositoryRequest;
import static com.java.semantic.query.application.SemanticQueryContract.SearchCodeRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticQueryFacadeSourceTest {
    private static final String REPOSITORY_ID = "payment-service";
    private static final String REVISION = "1".repeat(40);
    private static final String PATH = "src/main/java/example/payment/PaymentClient.java";
    private static final SourceRange FACT_RANGE = new SourceRange(PATH,
            new SyntaxRange(new SyntaxPosition(41, 4), new SyntaxPosition(41, 26)));
    private static final SourceRange CONTEXT_RANGE = new SourceRange(PATH,
            new SyntaxRange(new SyntaxPosition(39, 0), new SyntaxPosition(44, 0)));

    private CurrentRepositoryQueryService repositoryService;
    private CodeFactSearchService searchService;
    private SourceSliceService sourceSliceService;
    private CodeFactReadService codeFactReadService;
    private PublishedDiscoveryQueryService discoveryQueryService;
    private PublishedEntryPointQueryService entryPointQueryService;
    private SemanticQueryFacade facade;
    private CurrentGeneration generation;
    private CodeFactIdentity relationIdentity;

    @BeforeEach
    void setUp() {
        repositoryService = mock(CurrentRepositoryQueryService.class);
        searchService = mock(CodeFactSearchService.class);
        sourceSliceService = mock(SourceSliceService.class);
        codeFactReadService = mock(CodeFactReadService.class);
        discoveryQueryService = mock(PublishedDiscoveryQueryService.class);
        entryPointQueryService = mock(PublishedEntryPointQueryService.class);
        facade = new SemanticQueryFacade(repositoryService, searchService, sourceSliceService, codeFactReadService,
                discoveryQueryService, entryPointQueryService, mock(PublishedRelationQueryService.class));
        generation = generation(REPOSITORY_ID, REVISION, "g-payment");
        relationIdentity = relationIdentity();
    }

    @Test
    void repository_catalog_exposes_only_id_and_current_revision() {
        CurrentGeneration unrelated = generation("accounts-service", "2".repeat(40), "g-accounts");
        when(repositoryService.listRepositories()).thenReturn(List.of(generation, unrelated));

        List<RepositoryItem> result = facade.listRepositories(new PageRequest(0, 20)).items();

        assertEquals(List.of(new RepositoryItem("accounts-service", "2".repeat(40)),
                new RepositoryItem(REPOSITORY_ID, REVISION)), result);
        assertEquals(List.of("repositoryId", "revision"), List.of(RepositoryItem.class.getRecordComponents())
                .stream().map(component -> component.getName()).toList());
    }

    @Test
    void repository_lookup_exposes_only_id_and_current_revision() {
        when(repositoryService.getRepository(REPOSITORY_ID)).thenReturn(generation);

        RepositoryItem result = facade.getRepository(new RepositoryRequest(REPOSITORY_ID));

        assertEquals(new RepositoryItem(REPOSITORY_ID, REVISION), result);
    }

    @Test
    void search_reuses_the_same_opaque_fact_id_for_the_same_authoritative_fact() {
        CodeFactSummary summary = summary(relationIdentity, FACT_RANGE);
        CodeFactSearchResult result = new CodeFactSearchResult(generation,
                new CodeFactSearchQuery(new RepositoryId(REPOSITORY_ID), new RepositoryRevision(REVISION), "charge", Set.of(),
                        Optional.empty(), 0, 20),
                List.of(summary), 1, false, new SourceIndexCoverage(0, List.of()));
        FactSourceSlice slice = new FactSourceSlice(generation, FACT_RANGE, FACT_RANGE, sourceWithFact());
        when(searchService.search(any(CodeFactSearchQuery.class))).thenReturn(result);
        when(sourceSliceService.factSource(any(), any(Integer.class))).thenReturn(slice);

        SemanticQueryContract.SearchCodeResult first = facade.searchCode(searchRequest());
        SemanticQueryContract.SearchCodeResult second = facade.searchCode(searchRequest());

        SemanticQueryContract.ProgramElement firstElement = (SemanticQueryContract.ProgramElement) first.items().getFirst();
        SemanticQueryContract.ProgramElement secondElement = (SemanticQueryContract.ProgramElement) second.items().getFirst();
        assertEquals(CodeFactId.from(relationIdentity).value(), firstElement.factId());
        assertEquals(firstElement.factId(), secondElement.factId());
        assertEquals("client.charge(request)", firstElement.source().code());
        assertFalse(firstElement.source().code().isBlank());
    }

    @Test
    void search_exposes_only_the_compact_authorized_source_coverage_summary() throws Exception {
        CodeFactSearchResult result = new CodeFactSearchResult(generation,
                new CodeFactSearchQuery(new RepositoryId(REPOSITORY_ID), new RepositoryRevision(REVISION), "charge", Set.of(),
                        Optional.empty(), 0, 20),
                List.of(), 0, false, new SourceIndexCoverage(3, List.of(
                new SourceIndexIssue("internal/hidden/Payment.java", "PARSE_ERROR"),
                new SourceIndexIssue("src/Orders.java", "UNRESOLVED_TYPE"),
                new SourceIndexIssue("src/Orders.java", "PARSE_ERROR"))));
        when(searchService.search(any(CodeFactSearchQuery.class))).thenReturn(result);

        String response = new ObjectMapper().writeValueAsString(facade.searchCode(searchRequest()));

        assertTrue(response.contains("\"sourceCoverage\":{\"indexedSourceCount\":3,\"issueCount\":3,\"issueCodes\":[\"PARSE_ERROR\",\"UNRESOLVED_TYPE\"]}"));
        assertFalse(response.contains("internal/hidden/Payment.java"));
    }

    @Test
    void fact_source_accepts_a_relation_fact_and_preserves_exact_code() {
        FactSourceSlice slice = new FactSourceSlice(generation, FACT_RANGE, FACT_RANGE, sourceWithFact());
        when(sourceSliceService.factSource(any(), any(Integer.class))).thenReturn(slice);

        FactSourceResult result = facade.getFactSource(new FactSourceRequest(REPOSITORY_ID, REVISION,
                CodeFactId.from(relationIdentity).value(), 0));

        assertEquals("client.charge(request)", result.source().code());
        assertEquals(new FactRange(42, 42), result.factRange());
    }

    @Test
    void fact_source_reports_the_expanded_source_range_separately_from_the_fact_range() {
        FactSourceSlice slice = new FactSourceSlice(generation, CONTEXT_RANGE, FACT_RANGE, sourceWithFact());
        when(sourceSliceService.factSource(any(), any(Integer.class))).thenReturn(slice);

        FactSourceResult result = facade.getFactSource(new FactSourceRequest(REPOSITORY_ID, REVISION,
                CodeFactId.from(relationIdentity).value(), 2));

        assertEquals(40, result.source().startLine());
        assertEquals(44, result.source().endLine());
        assertEquals(new FactRange(42, 42), result.factRange());
        assertTrue(result.source().code().contains("client.charge(request)"));
    }

    private static SemanticQueryContract.SearchCodeRequest searchRequest() {
        return new SearchCodeRequest(REPOSITORY_ID, REVISION, "charge", Set.of(), Optional.empty(), 0, 20);
    }

    private static CurrentGeneration generation(String repositoryId, String revision, String generationId) {
        return new CurrentGeneration(new RepositoryId(repositoryId), new RepositoryRevision(revision), new GenerationId(generationId),
                new ManifestDigest("a".repeat(64)), Instant.parse("2026-09-02T00:00:00Z"));
    }

    private static CodeFactIdentity relationIdentity() {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("example.payment", "PaymentClient"), PATH);
        CodeFactIdentity method = new CodeFactIdentity(new RepositoryId(REPOSITORY_ID), new RepositoryRevision(REVISION), CodeFactKind.METHOD,
                new MethodTarget(type, "charge", List.of()));
        RelationIdentity relation = new RelationIdentity(method, RelationKind.CALLS_OUTBOUND_API,
                new RelationTarget.External(new ExternalTarget.Endpoint("POST", "https://payments.example/charge")), FACT_RANGE);
        return new CodeFactIdentity(new RepositoryId(REPOSITORY_ID), new RepositoryRevision(REVISION), CodeFactKind.OUTBOUND_API, relation);
    }

    private static CodeFactSummary summary(CodeFactIdentity identity, SourceRange range) {
        return new CodeFactSummary(new CodeFact(CodeFactId.from(identity), identity), range);
    }

    private static String sourceWithFact() {
        return "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10\n"
                + "line 11\nline 12\nline 13\nline 14\nline 15\nline 16\nline 17\nline 18\nline 19\nline 20\n"
                + "line 21\nline 22\nline 23\nline 24\nline 25\nline 26\nline 27\nline 28\nline 29\nline 30\n"
                + "line 31\nline 32\nline 33\nline 34\nline 35\nline 36\nline 37\nline 38\nline 39\nline 40\n"
                + "line 41\n    client.charge(request);\nline 43\nline 44\nline 45\n";
    }
}
