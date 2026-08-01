package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.ConceptAuthority;
import com.java.semantic.syntax.application.ConceptCatalogEntry;
import com.java.semantic.syntax.application.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.ConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.ConceptIssueReason;
import com.java.semantic.syntax.application.ConceptIssueSummary;
import com.java.semantic.syntax.application.ConceptKind;
import com.java.semantic.syntax.application.ConceptKindUnavailableException;
import com.java.semantic.syntax.application.ConceptMatchMode;
import com.java.semantic.syntax.application.ConceptPage;
import com.java.semantic.syntax.application.ConceptSearchQuery;
import com.java.semantic.syntax.application.ConceptSearchResult;
import com.java.semantic.syntax.application.ConceptSearchTerm;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.FieldTypeMember;
import com.java.semantic.syntax.application.MethodTypeMember;
import com.java.semantic.syntax.application.MapperStatementMethodMapping;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.application.TypeMemberKind;
import com.java.semantic.syntax.application.TypeMemberLimitation;
import com.java.semantic.syntax.application.TypeMemberQuery;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.application.TypeMemberTypeNotFoundException;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 結構化概念與型別成員 HTTP 邊界契約 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class StructuredDiscoveryControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REQUESTED_REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final RepositoryRevision ANALYZED_REVISION = RepositoryRevision.ofSha("2".repeat(40));
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderService.java";
    private static final MethodTarget METHOD_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderService"),
                        SOURCE_FILE),
                "createOrder",
                List.of("com.example.Order"));
    private static final MethodTarget MAPPER_TARGET_A = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderMapper"),
                        "module-a/src/main/java/com/example/OrderMapper.java"),
                "findOrders",
                List.of("java.lang.String"));
    private static final MethodTarget MAPPER_TARGET_Z = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderMapper"),
                        "module-z/src/main/java/com/example/OrderMapper.java"),
                "findOrders",
                List.of("java.lang.Long"));
    private static final List<ConceptKind> ACTIVE_CONCEPT_KINDS = List.of(
            ConceptKind.TYPE,
            ConceptKind.METHOD,
            ConceptKind.FIELD,
            ConceptKind.ANNOTATION_USAGE,
            ConceptKind.TYPE_USAGE,
            ConceptKind.API_ROUTE,
            ConceptKind.MQ_DESTINATION,
            ConceptKind.SCHEDULE,
            ConceptKind.MAPPER_STATEMENT);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptDiscoveryApplicationService conceptDiscoveryApplicationService;

    @MockitoBean
    private TypeMemberDiscoveryApplicationService typeMemberDiscoveryApplicationService;

    @Test
    void should_map_concept_request_and_return_identity_derived_candidates_with_complete_next_page() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(conceptResult());

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[
                    {"value":"Order*","matchMode":"TOKEN_EXACT"},
                    {"value":"Service","matchMode":"TOKEN_EXACT"}
                  ],
                  "kinds":["FIELD","METHOD"],
                  "packagePrefix":"com.example"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision")
                        .value("2222222222222222222222222222222222222222"))
                .andExpect(jsonPath("$.normalizedTerms[0]").value("order"))
                .andExpect(jsonPath("$.normalizedTerms[1]").value("service"))
                .andExpect(jsonPath("$.searchedKinds[0]").value("METHOD"))
                .andExpect(jsonPath("$.searchedKinds[1]").value("FIELD"))
                .andExpect(jsonPath("$.supportedKinds[0]").value("TYPE"))
                .andExpect(jsonPath("$.supportedKinds[1]").value("METHOD"))
                .andExpect(jsonPath("$.supportedKinds[2]").value("FIELD"))
                .andExpect(jsonPath("$.supportedKinds[3]").value("ANNOTATION_USAGE"))
                .andExpect(jsonPath("$.supportedKinds[4]").value("TYPE_USAGE"))
                .andExpect(jsonPath("$.supportedKinds[5]").value("API_ROUTE"))
                .andExpect(jsonPath("$.supportedKinds[6]").value("MQ_DESTINATION"))
                .andExpect(jsonPath("$.supportedKinds[7]").value("SCHEDULE"))
                .andExpect(jsonPath("$.limitations[0]").value("SOURCE_BODY_NOT_SEARCHED"))
                .andExpect(jsonPath("$.candidates[0].kind").value("FIELD"))
                .andExpect(jsonPath("$.candidates[0].matchedTerms[0]").value("order"))
                .andExpect(jsonPath("$.candidates[0].matchedTerms[1]").value("service"))
                .andExpect(jsonPath("$.candidates[0].subject")
                        .value(SOURCE_FILE + "::com.example.OrderService#repository:OrderRepository"))
                .andExpect(jsonPath("$.candidates[0].target").isEmpty())
                .andExpect(jsonPath("$.candidates[0].evidence[0].kind").value("METHOD"))
                .andExpect(jsonPath("$.candidates[0].evidence[0].subject").isEmpty())
                .andExpect(jsonPath("$.candidates[0].evidence[0].target.methodName")
                        .value("createOrder"))
                .andExpect(jsonPath("$.candidates[0].evidence[0].canonicalValue").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].evidence[1].kind").value("FIELD"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].subject")
                        .value(SOURCE_FILE + "::com.example.OrderService#repository:OrderRepository"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].target").isEmpty())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].operation")
                        .value("GET_TYPE_MEMBERS"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.method")
                        .value("POST"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/type-members"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.operationId")
                        .value("discoverTypeMembers"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.repoId")
                        .value("orders"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.expectedRevision")
                        .value(ANALYZED_REVISION.value()))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath(
                        "$.candidates[0].availableFollowUps[0].request.fullyQualifiedName")
                        .value("com.example.OrderService"))
                .andExpect(jsonPath(
                        "$.candidates[0].availableFollowUps[0].request.fullyQualifiedTypeName")
                        .doesNotExist())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.memberKinds[0]")
                        .value("METHOD"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.memberKinds[1]")
                        .value("FIELD"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.namePrefix")
                        .isEmpty())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.offset").value(0))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.limit").value(50))
                .andExpect(jsonPath("$.candidates[1].kind").value("METHOD"))
                .andExpect(jsonPath("$.candidates[1].subject").isEmpty())
                .andExpect(jsonPath("$.candidates[1].target.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.candidates[1].target.packageName").value("com.example"))
                .andExpect(jsonPath("$.candidates[1].target.className").value("OrderService"))
                .andExpect(jsonPath("$.candidates[1].target.methodName").value("createOrder"))
                .andExpect(jsonPath("$.candidates[1].target.parameterTypes[0]").value("com.example.Order"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps.length()").value(4))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-source"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[1].operation")
                        .value("ANALYZE_OUTGOING_CALL_GRAPH"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[2].operation")
                        .value("ANALYZE_INCOMING_CALL_GRAPH"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[3].operation")
                        .value("DISCOVER_METHOD_IMPLEMENTATIONS"))
                .andExpect(jsonPath("$.page.offset").value(0))
                .andExpect(jsonPath("$.page.limit").value(2))
                .andExpect(jsonPath("$.page.returnedCount").value(2))
                .andExpect(jsonPath("$.page.totalCount").value(4))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.coverage.status").value("PARTIAL"))
                .andExpect(jsonPath("$.coverage.scannedFileCount").value(2))
                .andExpect(jsonPath("$.coverage.extractedFileCount").value(1))
                .andExpect(jsonPath("$.coverage.syntaxFailedFileCount").value(1))
                .andExpect(jsonPath("$.issueSummaries[0].reason")
                        .value("MQ_DESTINATION_UNRESOLVED"))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("GET_NEXT_PAGE"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.method").value("POST"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.path").value("/v1/discovery/concepts"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.operationId").value("discoverConcepts"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.repoId").value("orders"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.expectedRevision")
                        .value("2222222222222222222222222222222222222222"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.terms[0].value").value("order"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.terms[0].matchMode")
                        .value("TOKEN_PREFIX"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.terms[1].value")
                        .value("service"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.terms[1].matchMode")
                        .value("TOKEN_EXACT"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.kinds[0]").value("FIELD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.kinds[1]").value("METHOD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.packagePrefix")
                        .value("com.example"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.offset").value(2))
                .andExpect(jsonPath("$.availableFollowUps[0].request.limit").value(2))
                .andExpect(jsonPath("$.unavailableFollowUps").isEmpty());

        ArgumentCaptor<ConceptSearchQuery> query = ArgumentCaptor.forClass(ConceptSearchQuery.class);
        then(conceptDiscoveryApplicationService).should().search(query.capture());
        assertThat(query.getValue()).isEqualTo(new ConceptSearchQuery(
                REPOSITORY_ID,
                REQUESTED_REVISION,
                List.of(
                        new ConceptSearchTerm("Order*", ConceptMatchMode.TOKEN_EXACT),
                        new ConceptSearchTerm("Service", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(ConceptKind.FIELD, ConceptKind.METHOD),
                Set.of("com.example"),
                0,
                50));
    }

    @Test
    void should_accept_mapper_statement_kind_and_return_deterministic_variant_identity_evidence() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(mapperConceptResult());

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"findOrders","matchMode":"TOKEN_EXACT"}],
                  "kinds":["MAPPER_STATEMENT"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchedKinds[0]").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.supportedKinds[8]").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].kind").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates[0].canonicalValue")
                        .value("com.example.OrderMapper#findOrders"))
                .andExpect(jsonPath("$.candidates[0].subject")
                        .value("com.example.OrderMapper#findOrders"))
                .andExpect(jsonPath("$.candidates[0].target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps").isEmpty())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.statementId")
                        .value("findOrders"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.status")
                        .value("RESOLVED"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.reason")
                        .doesNotExist())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.candidates.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].target.sourceFile")
                        .value(MAPPER_TARGET_A.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SQL"))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-sql"))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[0].request.target.parameterTypes[0]")
                        .value("java.lang.String"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].resourcePath")
                        .value("module-a/src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].databaseId").value("postgres"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].documentOrdinal").value(1))
                .andExpect(jsonPath("$.candidates[0].evidence[1].representation")
                        .value("MAPPER_XML_ELEMENT"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].resourcePath")
                        .value("module-b/src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].databaseId").value("oracle"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].documentOrdinal").value(0))
                .andExpect(jsonPath("$.candidates[0].evidence[2].representation")
                        .value("MAPPER_XML_ELEMENT"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].content").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].evidence[2].content").doesNotExist());

        ArgumentCaptor<ConceptSearchQuery> query = ArgumentCaptor.forClass(ConceptSearchQuery.class);
        then(conceptDiscoveryApplicationService).should().search(query.capture());
        assertThat(query.getValue().kinds()).containsExactly(ConceptKind.MAPPER_STATEMENT);
    }

    @Test
    void should_retain_every_ambiguous_mapper_method_with_only_source_follow_up() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(
                mapperConceptResult(List.of(MAPPER_TARGET_Z, MAPPER_TARGET_A, MAPPER_TARGET_A)));

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"findOrders","matchMode":"TOKEN_EXACT"}],
                  "kinds":["MAPPER_STATEMENT"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.status")
                        .value("AMBIGUOUS"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.reason")
                        .doesNotExist())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.candidates.length()")
                        .value(2))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].target.sourceFile")
                        .value(MAPPER_TARGET_A.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[1].target.sourceFile")
                        .value(MAPPER_TARGET_Z.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[1].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[1].availableFollowUps.length()")
                        .value(1));
    }

    @Test
    void should_retain_unresolved_mapper_identity_and_reason_without_follow_up() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(
                mapperConceptResult(List.of()));

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"findOrders","matchMode":"TOKEN_EXACT"}],
                  "kinds":["MAPPER_STATEMENT"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.statementId")
                        .value("findOrders"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.status")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.reason")
                        .value("INCOMPLETE_METHOD_RESOLUTION"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.candidates")
                        .isEmpty())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps").isEmpty());
    }

    @Test
    void should_expose_only_source_follow_up_for_resolved_candidate_in_unresolved_mapper_mapping() throws Exception {
        MapperStatementConceptIdentity identity =
                new MapperStatementConceptIdentity("com.example.OrderMapper", "findOrders");
        MapperStatementMethodMapping mapping = new MapperStatementMethodMapping(
                identity,
                MapperStatementMethodMapping.Status.UNRESOLVED,
                List.of(MAPPER_TARGET_A),
                Optional.of(MapperStatementMethodMapping.Reason.INCOMPLETE_METHOD_RESOLUTION));
        given(conceptDiscoveryApplicationService.search(any())).willReturn(mapperConceptResult(mapping));

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"findOrders","matchMode":"TOKEN_EXACT"}],
                  "kinds":["MAPPER_STATEMENT"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.status")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.reason")
                        .value("INCOMPLETE_METHOD_RESOLUTION"))
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping.candidates.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath(
                        "$.candidates[0].mapperStatementMapping.candidates[0].availableFollowUps[?(@.operation == 'GET_METHOD_SQL')]")
                        .isEmpty());
    }

    @Test
    void should_return_refinement_guidance_for_complete_zero_result_search() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(
                emptyConceptResult(completeCoverage()));

        mockMvc.perform(conceptRequest(validConceptRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.page.offset").value(0))
                .andExpect(jsonPath("$.page.limit").value(50))
                .andExpect(jsonPath("$.page.returnedCount").value(0))
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.coverage.status").value("COMPLETE"))
                .andExpect(jsonPath("$.availableFollowUps").isEmpty())
                .andExpect(jsonPath("$.unavailableFollowUps[0].reason")
                        .value("NO_MATCHING_STRUCTURED_CONCEPT"))
                .andExpect(jsonPath("$.unavailableFollowUps[0].recommendedAction")
                        .value("REFINE_TERMS_KINDS_OR_PACKAGE_FILTERS"))
                .andExpect(jsonPath("$.unavailableFollowUps[0].api").doesNotExist())
                .andExpect(jsonPath("$.unavailableFollowUps[0].request").doesNotExist());
    }

    @Test
    void should_return_repair_guidance_for_partial_zero_result_search() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(
                emptyConceptResult(coverage()));

        mockMvc.perform(conceptRequest(validConceptRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.coverage.status").value("PARTIAL"))
                .andExpect(jsonPath("$.unavailableFollowUps[0].reason")
                        .value("SEARCH_INCOMPLETE"))
                .andExpect(jsonPath("$.unavailableFollowUps[0].recommendedAction")
                        .value("FIX_SOURCE_OR_RETRY"))
                .andExpect(jsonPath("$.unavailableFollowUps[0].api").doesNotExist())
                .andExpect(jsonPath("$.unavailableFollowUps[0].request").doesNotExist());
    }

    @Test
    void should_return_discriminated_type_members_with_complete_analysis_and_next_page_follow_ups()
            throws Exception {
        given(typeMemberDiscoveryApplicationService.discover(any())).willReturn(typeMemberResult());

        mockMvc.perform(typeMemberRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "sourceFile":"src/main/java/com/example/OrderService.java",
                  "fullyQualifiedName":"com.example.OrderService",
                  "memberKinds":["METHOD","FIELD"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.fullyQualifiedName").value("com.example.OrderService"))
                .andExpect(jsonPath("$.typeKind").value("CLASS"))
                .andExpect(jsonPath("$.members[0].kind").value("METHOD"))
                .andExpect(jsonPath("$.members[0].target.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.members[0].target.packageName").value("com.example"))
                .andExpect(jsonPath("$.members[0].target.className").value("OrderService"))
                .andExpect(jsonPath("$.members[0].target.methodName").value("createOrder"))
                .andExpect(jsonPath("$.members[0].target.parameterTypes[0]").value("com.example.Order"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[0].api.method").value("POST"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-source"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[0].api.operationId")
                        .value("getMethodSource"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[0].request.target.methodName")
                        .value("createOrder"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[1].operation")
                        .value("ANALYZE_OUTGOING_CALL_GRAPH"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[1].request.depth").value(2))
                .andExpect(jsonPath("$.members[0].availableFollowUps[1].request.target.parameterTypes[0]")
                        .value("com.example.Order"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[2].operation")
                        .value("ANALYZE_INCOMING_CALL_GRAPH"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[3].operation")
                        .value("DISCOVER_METHOD_IMPLEMENTATIONS"))
                .andExpect(jsonPath("$.members[0].availableFollowUps[3].request.declarationTarget.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.members[1].kind").value("FIELD"))
                .andExpect(jsonPath("$.members[1].fieldName").value("repository"))
                .andExpect(jsonPath("$.members[1].writtenType").value("OrderRepository[][]"))
                .andExpect(jsonPath("$.members[1].resolvedType").value("com.example.OrderRepository[][]"))
                .andExpect(jsonPath("$.members[1].limitations[0]").value("FIELD_USAGE_NOT_INDEXED"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].operation")
                        .value("DISCOVER_CONCEPTS"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.terms[0].value")
                        .value("com.example.OrderRepository[][]"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.terms[0].matchMode")
                        .value("CANONICAL_EXACT"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.kinds[0]")
                        .value("TYPE"))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("GET_NEXT_PAGE"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.path")
                        .value("/v1/discovery/type-members"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.operationId")
                        .value("discoverTypeMembers"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.availableFollowUps[0].request.fullyQualifiedName")
                        .value("com.example.OrderService"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.memberKinds[0]").value("METHOD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.memberKinds[1]").value("FIELD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.offset").value(2))
                .andExpect(jsonPath("$.availableFollowUps[0].request.limit").value(2));

        ArgumentCaptor<TypeMemberQuery> query = ArgumentCaptor.forClass(TypeMemberQuery.class);
        then(typeMemberDiscoveryApplicationService).should().discover(query.capture());
        assertThat(query.getValue()).isEqualTo(new TypeMemberQuery(
                REPOSITORY_ID,
                REQUESTED_REVISION,
                SOURCE_FILE,
                "com.example.OrderService",
                Set.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                50));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void should_return_request_invalid_for_closed_or_invalid_transport_values(
            String path,
            String body) throws Exception {
        String requestBody = path.equals("/v1/discovery/concepts") ? withAllOperator(body) : body;
        mockMvc.perform(post(path)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
        then(typeMemberDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_preserve_revision_mismatch_contract() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willThrow(
                new RepositoryRevisionMismatchException(REQUESTED_REVISION, ANALYZED_REVISION));

        mockMvc.perform(conceptRequest(validConceptRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"))
                .andExpect(jsonPath("$.expectedRevision").value(REQUESTED_REVISION.value()))
                .andExpect(jsonPath("$.currentRevision").value(ANALYZED_REVISION.value()));
    }

    @Test
    void should_reject_duplicate_concept_kinds_before_dispatch() throws Exception {
        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                  "kinds":["METHOD","METHOD"]
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_require_the_all_operator_for_concept_discovery() throws Exception {
        mockMvc.perform(post("/v1/discovery/concepts")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConceptRequest().replace("\"operator\":\"ALL\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_return_typed_unavailable_kind_contract() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willThrow(new ConceptKindUnavailableException(
                Set.of(ConceptKind.SQL_IDENTIFIER), Set.of(ConceptKind.TYPE, ConceptKind.METHOD)));

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                  "kinds":["SQL_IDENTIFIER"]
                }
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CONCEPT_KIND_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableKinds[0]").value("SQL_IDENTIFIER"))
                .andExpect(jsonPath("$.supportedKinds[0]").value("TYPE"))
                .andExpect(jsonPath("$.supportedKinds[1]").value("METHOD"));
    }

    @Test
    void should_return_not_found_when_the_source_qualified_type_is_missing() throws Exception {
        given(typeMemberDiscoveryApplicationService.discover(any())).willThrow(new TypeMemberTypeNotFoundException());

        mockMvc.perform(typeMemberRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "sourceFile":"src/main/java/com/example/Missing.java",
                  "fullyQualifiedName":"com.example.Missing",
                  "memberKinds":["METHOD"]
                }
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TYPE_MEMBER_TYPE_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("structuredDiscoveryPaths")
    void should_require_api_token_for_structured_discovery(String path) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_UNAUTHORIZED"));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
        then(typeMemberDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    private static Stream<String> structuredDiscoveryPaths() {
        return Stream.of("/v1/discovery/concepts", "/v1/discovery/type-members");
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                          "kinds":["METHOD"],
                          "unexpected":true
                        }
                        """),
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"UNKNOWN"}],
                          "kinds":["METHOD"]
                        }
                        """),
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                          "kinds":["UNKNOWN"]
                        }
                        """),
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                          "kinds":["METHOD"],
                          "offset":-1
                        }
                        """),
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                          "kinds":["METHOD"],
                          "offset":null
                        }
                        """),
                arguments("/v1/discovery/concepts", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                          "kinds":["METHOD"],
                          "limit":101
                        }
                        """),
                arguments("/v1/discovery/type-members", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "sourceFile":"src/main/java/com/example/OrderService.java",
                          "fullyQualifiedName":"com.example.OrderService",
                          "memberKinds":["UNKNOWN"]
                        }
                        """),
                arguments("/v1/discovery/type-members", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "sourceFile":"src/main/java/com/example/OrderService.java",
                          "fullyQualifiedName":"com.example.OrderService",
                          "memberKinds":["METHOD"],
                          "limit":0
                        }
                        """),
                arguments("/v1/discovery/type-members", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "sourceFile":"src/main/java/com/example/OrderService.java",
                          "fullyQualifiedTypeName":"com.example.OrderService",
                          "memberKinds":["METHOD"]
                        }
                        """));
    }

    private static Arguments arguments(String path, String body) {
        return Arguments.of(path, body);
    }

    private static MockHttpServletRequestBuilder conceptRequest(String body) {
        return post("/v1/discovery/concepts")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withAllOperator(body));
    }

    private static MockHttpServletRequestBuilder typeMemberRequest(String body) {
        return post("/v1/discovery/type-members")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String validConceptRequest() {
        return """
                {
                  "operator":"ALL",
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                  "kinds":["METHOD"]
                }
                """;
    }

    private static String withAllOperator(String body) {
        return body.contains("\"operator\"") ? body
                : body.replaceFirst("\\{", "{\n  \"operator\":\"ALL\",");
    }

    private static ConceptSearchResult conceptResult() {
        FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(
                SOURCE_FILE,
                "com.example.OrderService",
                "repository",
                "OrderRepository");
        MethodConceptIdentity methodIdentity = new MethodConceptIdentity(METHOD_TARGET);
        ConceptCatalogEntry field = conceptEntry(
                fieldIdentity,
                "com.example.OrderService#repository",
                "OrderService.repository",
                Optional.of("com.example.OrderService"),
                Set.of(fieldIdentity, methodIdentity));
        ConceptCatalogEntry method = conceptEntry(
                methodIdentity,
                "com.example.OrderService#createOrder(com.example.Order)",
                "OrderService.createOrder",
                Optional.of("com.example.OrderService"),
                Set.of(methodIdentity));
        ConceptSearchQuery nextQuery = new ConceptSearchQuery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(
                        new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX),
                        new ConceptSearchTerm("service", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(ConceptKind.FIELD, ConceptKind.METHOD),
                Set.of("com.example"),
                2,
                2);
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(
                        new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX),
                        new ConceptSearchTerm("service", ConceptMatchMode.TOKEN_EXACT)),
                List.of(ConceptKind.METHOD, ConceptKind.FIELD),
                ACTIVE_CONCEPT_KINDS,
                List.of(field, method),
                new ConceptPage(0, 2, 2, 4, true),
                coverage(),
                List.of(new ConceptIssueSummary(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED, 2)),
                Optional.of(nextQuery));
    }

    private static ConceptSearchResult emptyConceptResult(
            List<SourceExtractionOutcome> extractionOutcomes) {
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX)),
                List.of(ConceptKind.METHOD),
                List.of(ConceptKind.METHOD),
                List.of(),
                new ConceptPage(0, 50, 0, 0, false),
                extractionOutcomes,
                List.of(),
                Optional.empty());
    }

    private static ConceptSearchResult mapperConceptResult() {
        return mapperConceptResult(List.of(MAPPER_TARGET_A));
    }

    private static ConceptSearchResult mapperConceptResult(List<MethodTarget> mappedTargets) {
        MapperStatementConceptIdentity identity =
                new MapperStatementConceptIdentity("com.example.OrderMapper", "findOrders");
        List<MethodTarget> distinctTargets = mappedTargets.stream().distinct().toList();
        return mapperConceptResult(MapperStatementMethodMapping.fromDeclarations(
                identity,
                distinctTargets.size(),
                distinctTargets.isEmpty(),
                distinctTargets));
    }

    private static ConceptSearchResult mapperConceptResult(MapperStatementMethodMapping mapping) {
        MapperStatementConceptIdentity identity = mapping.statementIdentity();
        MapperStatementIdentity postgresIdentity = new MapperStatementIdentity(
                "com.example.OrderMapper",
                "findOrders",
                "module-a/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity oracleIdentity = new MapperStatementIdentity(
                "com.example.OrderMapper",
                "findOrders",
                "module-b/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("oracle"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        ConceptCatalogEntry mapper = new ConceptCatalogEntry(
                "test",
                identity,
                "com.example.OrderMapper#findOrders",
                "com.example.OrderMapper#findOrders",
                Set.of("find", "orders"),
                "",
                Optional.of("com.example.OrderMapper"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(
                        identity,
                        new MapperStatementVariantEvidenceIdentity(oracleIdentity),
                        new MapperStatementVariantEvidenceIdentity(postgresIdentity)),
                Optional.of(mapping));
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(new ConceptSearchTerm("findOrders", ConceptMatchMode.TOKEN_EXACT)),
                List.of(ConceptKind.MAPPER_STATEMENT),
                ACTIVE_CONCEPT_KINDS,
                List.of(mapper),
                new ConceptPage(0, 50, 1, 1, false),
                completeCoverage(),
                List.of(),
                Optional.empty());
    }

    private static ConceptCatalogEntry conceptEntry(
            ConceptIdentity identity,
            String canonicalValue,
            String displayValue,
            Optional<String> declaringType,
            Set<ConceptIdentity> evidence) {
        return new ConceptCatalogEntry(
                "test",
                identity,
                canonicalValue,
                displayValue,
                Set.of("order"),
                "com.example",
                declaringType,
                ConceptAuthority.SYNTAX_RESOLVED,
                evidence);
    }

    private static TypeMemberResult typeMemberResult() {
        DiscoveryFollowUpFactory followUpFactory = new DiscoveryFollowUpFactory();
        MethodTypeMember method = new MethodTypeMember(
                METHOD_TARGET,
                followUpFactory.forMethod(REPOSITORY_ID, ANALYZED_REVISION, METHOD_TARGET));
        FieldTypeMember field = new FieldTypeMember(
                "repository",
                "OrderRepository[][]",
                Optional.of("com.example.OrderRepository[][]"),
                List.of("org.springframework.beans.factory.annotation.Autowired"),
                List.of(TypeMemberLimitation.FIELD_USAGE_NOT_INDEXED),
                followUpFactory.forResolvedFieldType(
                        REPOSITORY_ID,
                        ANALYZED_REVISION,
                        Optional.of("com.example.OrderRepository[][]")));
        TypeMemberQuery nextQuery = new TypeMemberQuery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                SOURCE_FILE,
                "com.example.OrderService",
                Set.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                2);
        DiscoveryFollowUp nextPage = followUpFactory.nextTypeMemberPage(nextQuery, 2);
        return new TypeMemberResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                SOURCE_FILE,
                "com.example.OrderService",
                SourceTypeKind.CLASS,
                List.of("org.springframework.stereotype.Service"),
                List.of("com.example.OrderPort"),
                List.of("com.example.BaseService"),
                List.of(method, field),
                new ConceptPage(0, 2, 2, 3, true),
                coverage(),
                List.of(nextPage));
    }

    private static List<SourceExtractionOutcome> coverage() {
        return List.of(
                SourceExtractionOutcome.extracted(SOURCE_FILE),
                SourceExtractionOutcome.syntaxFailed(
                        "src/main/java/com/example/Broken.java",
                        "SYNTAX_ERROR"));
    }

    private static List<SourceExtractionOutcome> completeCoverage() {
        return List.of(SourceExtractionOutcome.extracted(SOURCE_FILE));
    }
}
