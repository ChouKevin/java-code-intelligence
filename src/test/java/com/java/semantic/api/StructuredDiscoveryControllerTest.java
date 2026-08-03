package com.java.semantic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.concept.ConceptAuthority;
import com.java.semantic.syntax.application.concept.ConceptCatalogEntry;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.ConceptIssueReason;
import com.java.semantic.syntax.application.concept.ConceptIssueSummary;
import com.java.semantic.syntax.application.concept.ConceptKind;
import com.java.semantic.syntax.application.concept.ConceptKindUnavailableException;
import com.java.semantic.syntax.application.concept.ConceptIdentityNotFoundException;
import com.java.semantic.syntax.application.concept.ConceptMatchMode;
import com.java.semantic.syntax.application.concept.ConceptPage;
import com.java.semantic.syntax.application.concept.ConceptSearchQuery;
import com.java.semantic.syntax.application.concept.ConceptSearchResult;
import com.java.semantic.syntax.application.concept.ConceptSearchTerm;
import com.java.semantic.syntax.application.concept.ConceptResolveQuery;
import com.java.semantic.syntax.application.concept.RevisionBoundConceptResolution;
import com.java.semantic.syntax.application.concept.ReferencedTypeIdentity;
import com.java.semantic.syntax.application.concept.FieldConceptDetails;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.application.concept.TypeUsagePath;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.FieldTypeMember;
import com.java.semantic.syntax.application.MethodTypeMember;
import com.java.semantic.syntax.application.concept.MapperStatementMethodMapping;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.application.TypeMemberKind;
import com.java.semantic.syntax.application.TypeMemberLimitation;
import com.java.semantic.syntax.application.TypeMemberQuery;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.application.TypeMemberTypeNotFoundException;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
                .andExpect(jsonPath("$.candidates[0].identity.kind").value("FIELD"))
                .andExpect(jsonPath("$.candidates[0].matchedTerms[0]").value("order"))
                .andExpect(jsonPath("$.candidates[0].matchedTerms[1]").value("service"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.scope").value("TYPE"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.ownerType.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.candidates[0].identity.identity.ownerType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.ownerType.javaType.className")
                        .value("OrderService"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.name").value("repository"))
                .andExpect(jsonPath("$.candidates[0].evidence[0].identity.kind").value("METHOD"))
                .andExpect(jsonPath("$.candidates[0].evidence[0].identity.target.methodName")
                        .value("createOrder"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].identity.kind").value("FIELD"))
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
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.sourceType.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath(
                        "$.candidates[0].availableFollowUps[0].request.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath(
                        "$.candidates[0].availableFollowUps[0].request.sourceType.javaType.className")
                        .value("OrderService"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.memberKinds[0]")
                        .value("METHOD"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.memberKinds[1]")
                        .value("FIELD"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.namePrefix")
                        .isEmpty())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.offset").value(0))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.limit").value(50))
                .andExpect(jsonPath("$.candidates[1].identity.kind").value("METHOD"))
                .andExpect(jsonPath("$.candidates[1].identity.target.sourceType.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.candidates[1].identity.target.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.candidates[1].identity.target.sourceType.javaType.className")
                        .value("OrderService"))
                .andExpect(jsonPath("$.candidates[1].identity.target.methodName").value("createOrder"))
                .andExpect(jsonPath("$.candidates[1].identity.target.parameterTypes[0]").value("com.example.Order"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps.length()").value(3))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-source"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[1].operation")
                        .value("ANALYZE_OUTGOING_CALL_GRAPH"))
                .andExpect(jsonPath("$.candidates[1].availableFollowUps[2].operation")
                        .value("ANALYZE_INCOMING_CALL_GRAPH"))
                .andExpect(jsonPath("$.page.offset").value(0))
                .andExpect(jsonPath("$.page.limit").value(2))
                .andExpect(jsonPath("$.page.returnedCount").value(2))
                .andExpect(jsonPath("$.page.totalCount").value(4))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.coverage.status").value("PARTIAL"))
                .andExpect(jsonPath("$.coverage.scannedFileCount").value(2))
                .andExpect(jsonPath("$.coverage.extractedFileCount").value(1))
                .andExpect(jsonPath("$.coverage.syntaxFailedFileCount").value(1))
                .andExpect(jsonPath("$.issueSummaries[0].code")
                        .value("MQ_DESTINATION_UNRESOLVED"))
                .andExpect(jsonPath("$.issueSummaries[0].reason").doesNotExist())
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("DISCOVER_CONCEPTS"))
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
    void should_project_closed_typed_identity_variants_and_recursive_field_details() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(typedIdentityResult());

        mockMvc.perform(conceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                  "kinds":["TYPE","METHOD","FIELD","ANNOTATION_USAGE","TYPE_USAGE","API_ROUTE","MQ_DESTINATION","SCHEDULE","MAPPER_STATEMENT"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(9))
                .andExpect(jsonPath("$.candidates[0].identity.kind").value("TYPE"))
                .andExpect(jsonPath("$.candidates[1].identity.kind").value("METHOD"))
                .andExpect(jsonPath("$.candidates[1].identity.target.sourceType.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.candidates[2].identity.kind").value("FIELD"))
                .andExpect(jsonPath("$.candidates[2].identity.identity.ownerType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.candidates[2].identity.identity.ownerType.javaType.className")
                        .value("OrderService"))
                .andExpect(jsonPath("$.candidates[2].details.kind").value("FIELD"))
                .andExpect(jsonPath("$.candidates[2].details.declaredType.kind").value("PARAMETERIZED"))
                .andExpect(jsonPath("$.candidates[2].details.declaredType.rawType.kind").value("NAMED"))
                .andExpect(jsonPath("$.candidates[2].details.declaredType.typeArguments[1].kind")
                        .value("PARAMETERIZED"))
                .andExpect(jsonPath("$.candidates[2].details.declaredType.typeArguments[1].typeArguments[0].kind")
                        .value("ARRAY"))
                .andExpect(jsonPath("$.candidates[2].details.declaredType.typeArguments[1].typeArguments[0].dimensions")
                        .value(1))
                .andExpect(jsonPath("$.candidates[3].identity.kind").value("ANNOTATION_USAGE"))
                .andExpect(jsonPath("$.candidates[4].identity.kind").value("TYPE_USAGE"))
                .andExpect(jsonPath("$.candidates[5].identity.kind").value("API_ROUTE"))
                .andExpect(jsonPath("$.candidates[6].identity.kind").value("MQ_DESTINATION"))
                .andExpect(jsonPath("$.candidates[7].identity.kind").value("SCHEDULE"))
                .andExpect(jsonPath("$.candidates[8].identity.kind").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates[8].details.kind").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates[8].evidence[1].identity.kind").value("MAPPER_STATEMENT_VARIANT"))
                .andExpect(jsonPath("$.candidates[8].evidence[1].identity.identity.resourcePath")
                        .value("module-a/src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.candidates[0].kind").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].sourceFile").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].subject").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].mapperStatementMapping").doesNotExist());
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
                .andExpect(jsonPath("$.candidates[0].identity.kind").value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].identity.identity.statementId")
                        .value("findOrders"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].operation")
                        .value("GET_EVIDENCE_SOURCE"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.method").value("POST"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/evidence-source"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.repoId").value("orders"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.expectedRevision")
                        .value(ANALYZED_REVISION.value()))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.identity.kind")
                        .value("MAPPER_STATEMENT"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.statement.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.statement.statementId")
                        .value("findOrders"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.namespace").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].details.mapping.statementId").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].details.mapping.status")
                        .value("RESOLVED"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.reason")
                        .doesNotExist())
                .andExpect(jsonPath("$.candidates[0].details.mapping.candidates.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].target.sourceType.sourceFile")
                        .value(MAPPER_TARGET_A.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].availableFollowUps.length()")
                        .value(0))
                .andExpect(jsonPath("$.candidates[0].evidence[1].identity.identity.resourcePath")
                        .value("module-a/src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].identity.identity.databaseId").value("postgres"))
                .andExpect(jsonPath("$.candidates[0].evidence[1].identity.identity.documentOrdinal").value(1))
                .andExpect(jsonPath("$.candidates[0].evidence[1].identity.identity.representation")
                        .value("MAPPER_XML_ELEMENT"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].identity.identity.resourcePath")
                        .value("module-b/src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].identity.identity.databaseId").value("oracle"))
                .andExpect(jsonPath("$.candidates[0].evidence[2].identity.identity.documentOrdinal").value(0))
                .andExpect(jsonPath("$.candidates[0].evidence[2].identity.identity.representation")
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
                .andExpect(jsonPath("$.candidates[0].details.mapping.status")
                        .value("AMBIGUOUS"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.reason")
                        .doesNotExist())
                .andExpect(jsonPath("$.candidates[0].details.mapping.candidates.length()")
                        .value(2))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].target.sourceType.sourceFile")
                        .value(MAPPER_TARGET_A.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[1].target.sourceType.sourceFile")
                        .value(MAPPER_TARGET_Z.sourceFile()))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[1].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[1].availableFollowUps.length()")
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
                .andExpect(jsonPath("$.candidates[0].details.mapping.statement.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.statement.statementId")
                        .value("findOrders"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.status")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.reason")
                        .value("INCOMPLETE_METHOD_RESOLUTION"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.candidates")
                        .isEmpty())
                .andExpect(jsonPath("$.candidates[0].availableFollowUps.length()").value(2));
    }

    @Test
    void should_expose_only_source_follow_up_for_resolved_candidate_in_unresolved_mapper_mapping() throws Exception {
        MapperStatementConceptIdentity identity =
                new MapperStatementConceptIdentity(
                        new MapperStatementKey("com.example.OrderMapper", "findOrders"));
        MapperStatementMethodMapping mapping = new MapperStatementMethodMapping(
                identity,
                MapperStatementMethodMapping.Status.UNRESOLVED,
                List.of(MAPPER_TARGET_A),
                Optional.of(MapperStatementMethodMapping.Reason.INCOMPLETE_METHOD_RESOLUTION),
                List.of(new MapperFragmentIdentity(
                        "com.example.OrderMapper",
                        "baseColumns",
                        "module-a/src/main/resources/mapper/OrderFragments.xml",
                        0,
                        MapperEvidenceRepresentation.MAPPER_XML_ELEMENT)));
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
                .andExpect(jsonPath("$.candidates[0].details.mapping.status")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.reason")
                        .value("INCOMPLETE_METHOD_RESOLUTION"))
                .andExpect(jsonPath("$.candidates[0].details.mapping.candidates.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.candidates[0].details.mapping.candidates[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[2].operation")
                        .value("GET_EVIDENCE_SOURCE"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[2].request.identity.kind")
                        .value("MAPPER_FRAGMENT"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[2].request.identity.fragmentIdentity.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[2].request.identity.fragmentIdentity.fragmentId")
                        .value("baseColumns"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[2].request.identity.fragmentIdentity.documentOrdinal")
                        .value(0));
    }

    @Test
    void should_return_executable_refinement_follow_up_for_complete_zero_result_search() throws Exception {
        given(conceptDiscoveryApplicationService.search(any())).willReturn(
                emptyConceptResult(completeCoverage()));

        mockMvc.perform(conceptRequest(unavailableConceptRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.page.offset").value(7))
                .andExpect(jsonPath("$.page.limit").value(25))
                .andExpect(jsonPath("$.page.returnedCount").value(0))
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.coverage.status").value("COMPLETE"))
                .andExpect(jsonPath("$.availableFollowUps[0].operation")
                        .value("DISCOVER_CONCEPTS"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.method").value("POST"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.path")
                        .value("/v1/discovery/concepts"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.repoId").value("orders"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.expectedRevision")
                        .value(ANALYZED_REVISION.value()))
                .andExpect(jsonPath("$.availableFollowUps[0].request.kinds[0]").value("FIELD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.kinds[1]").value("METHOD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.packagePrefix")
                        .value("com.example.narrow"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.offset").value(7))
                .andExpect(jsonPath("$.availableFollowUps[0].request.limit").value(25))
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

        mockMvc.perform(conceptRequest(unavailableConceptRequest()))
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
                  "sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},
                  "memberKinds":["METHOD","FIELD"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.sourceType.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.sourceType.javaType.packageName").value("com.example"))
                .andExpect(jsonPath("$.sourceType.javaType.className").value("OrderService"))
                .andExpect(jsonPath("$.typeKind").value("CLASS"))
                .andExpect(jsonPath("$.members[0].kind").value("METHOD"))
                .andExpect(jsonPath("$.members[0].target.sourceType.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.members[0].target.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.members[0].target.sourceType.javaType.className")
                        .value("OrderService"))
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
                .andExpect(jsonPath("$.members[1].kind").value("FIELD"))
                .andExpect(jsonPath("$.members[1].identity.scope").value("TYPE"))
                .andExpect(jsonPath("$.members[1].identity.name").value("repository"))
                .andExpect(jsonPath("$.members[1].writtenType").value("OrderRepository[][]"))
                .andExpect(jsonPath("$.members[1].resolvedType").value("com.example.OrderRepository[][]"))
                .andExpect(jsonPath("$.members[1].limitations[0]").value("FIELD_USAGE_NOT_INDEXED"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].operation")
                        .value("RESOLVE_CONCEPT"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].api.path")
                        .value("/v1/discovery/concepts/resolve"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].api.operationId")
                        .value("resolveConcept"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.kind")
                        .value("TYPE"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.sourceFile")
                        .value("src/main/java/com/example/OrderRepository.java"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.javaType.className")
                        .value("OrderRepository"))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("DISCOVER_TYPE_MEMBERS"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.path")
                        .value("/v1/discovery/type-members"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.operationId")
                        .value("discoverTypeMembers"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.sourceType.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.availableFollowUps[0].request.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.sourceType.javaType.className")
                        .value("OrderService"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.memberKinds[0]").value("METHOD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.memberKinds[1]").value("FIELD"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.offset").value(2))
                .andExpect(jsonPath("$.availableFollowUps[0].request.limit").value(2));

        ArgumentCaptor<TypeMemberQuery> query = ArgumentCaptor.forClass(TypeMemberQuery.class);
        then(typeMemberDiscoveryApplicationService).should().discover(query.capture());
        assertThat(query.getValue()).isEqualTo(new TypeMemberQuery(
                REPOSITORY_ID,
                REQUESTED_REVISION,
                sourceType(),
                Set.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                50));
    }

    @Test
    void should_execute_field_type_follow_up_as_typed_concept_resolution() throws Exception {
        given(typeMemberDiscoveryApplicationService.discover(any())).willReturn(typeMemberResult());
        TypeConceptIdentity typeIdentity = new TypeConceptIdentity(new SourceTypeIdentity(
                new JavaTypeIdentity("com.example", "OrderRepository"),
                "src/main/java/com/example/OrderRepository.java"));
        given(conceptDiscoveryApplicationService.resolve(any())).willReturn(new RevisionBoundConceptResolution(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                conceptEntry(
                        typeIdentity,
                        "OrderRepository",
                        Optional.of("com.example.OrderRepository"),
                        Set.<ConceptIdentity>of(typeIdentity))));

        mockMvc.perform(typeMemberRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},
                  "memberKinds":["FIELD"]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].operation")
                        .value("RESOLVE_CONCEPT"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].api.path")
                        .value("/v1/discovery/concepts/resolve"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.repoId").value("orders"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.expectedRevision")
                        .value("2222222222222222222222222222222222222222"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.kind")
                        .value("TYPE"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.sourceFile")
                        .value("src/main/java/com/example/OrderRepository.java"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.members[1].availableFollowUps[0].request.identity.sourceType.javaType.className")
                        .value("OrderRepository"));

        mockMvc.perform(resolveConceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"2222222222222222222222222222222222222222",
                  "identity":{
                    "kind":"TYPE",
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderRepository"},"sourceFile":"src/main/java/com/example/OrderRepository.java"}
                  }
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.identity.kind").value("TYPE"))
                .andExpect(jsonPath("$.candidate.identity.sourceType.sourceFile")
                        .value("src/main/java/com/example/OrderRepository.java"))
                .andExpect(jsonPath("$.candidate.identity.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.candidate.identity.sourceType.javaType.className")
                        .value("OrderRepository"));
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

    @ParameterizedTest
    @MethodSource("resolveConceptRequests")
    void should_round_trip_every_concrete_concept_identity_variant(
            String body,
            String identityKind) throws Exception {
        given(conceptDiscoveryApplicationService.resolve(any())).willAnswer(invocation ->
                resolveConceptResult(invocation.getArgument(0)));

        String response = mockMvc.perform(resolveConceptRequest(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.identity.kind").value(identityKind))
                .andReturn().getResponse().getContentAsString();

        JsonNode expectedIdentity = OBJECT_MAPPER.readTree(body).path("identity");
        JsonNode actualIdentity = OBJECT_MAPPER.readTree(response).path("candidate").path("identity");
        assertThat(actualIdentity).isEqualTo(expectedIdentity);
    }

    @ParameterizedTest
    @MethodSource("malformedResolveConceptTypeIdentities")
    void should_reject_malformed_typed_coordinates_before_concept_resolve(String identity) throws Exception {
        mockMvc.perform(resolveConceptRequest(resolveConceptIdentity(identity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_resolve_field_owned_annotation_without_declared_type() throws Exception {
        given(conceptDiscoveryApplicationService.resolve(any())).willAnswer(invocation ->
                resolveConceptResult(invocation.getArgument(0)));

        mockMvc.perform(resolveConceptRequest(resolveConceptIdentity("""
                {"kind":"ANNOTATION_USAGE","declaration":{"kind":"FIELD","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"name":"orders"}},"annotationType":{"status":"UNRESOLVED","writtenName":"Autowired"}}
                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.identity.declaration.kind").value("FIELD"))
                .andExpect(jsonPath("$.candidate.identity.declaration.declaredType").doesNotExist());
    }

    @Test
    void should_resolve_field_identity_without_declared_type_and_return_type_only_in_details() throws Exception {
        FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(new TypeMember(sourceType(), "orders"));
        given(conceptDiscoveryApplicationService.resolve(any())).willReturn(new RevisionBoundConceptResolution(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                fieldConceptEntry(fieldIdentity)));

        mockMvc.perform(resolveConceptRequest(resolveConceptIdentity("""
                {"kind":"FIELD","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"name":"orders"}}
                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.identity.declaredType").doesNotExist())
                .andExpect(jsonPath("$.candidate.details.kind").value("FIELD"))
                .andExpect(jsonPath("$.candidate.details.declaredType.writtenType")
                        .value("Map<String, List<Order[]>>"));
    }

    @Test
    void should_reject_unknown_resolve_concept_identity_field() throws Exception {
        mockMvc.perform(resolveConceptRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"unexpected":true}
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
    }

    @Test
    void should_return_typed_not_found_for_absent_resolve_concept_identity() throws Exception {
        given(conceptDiscoveryApplicationService.resolve(any())).willThrow(new ConceptIdentityNotFoundException());

        mockMvc.perform(resolveConceptRequest(resolveConceptTypeIdentity()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONCEPT_IDENTITY_NOT_FOUND"));
    }

    @Test
    void should_preserve_revision_mismatch_for_resolve_concept() throws Exception {
        given(conceptDiscoveryApplicationService.resolve(any())).willThrow(
                new RepositoryRevisionMismatchException(REQUESTED_REVISION, ANALYZED_REVISION));

        mockMvc.perform(resolveConceptRequest(resolveConceptTypeIdentity()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"));
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
                  "sourceType":{"javaType":{"packageName":"com.example","className":"Missing"},"sourceFile":"src/main/java/com/example/Missing.java"},
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
                          "sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},
                          "memberKinds":["UNKNOWN"]
                        }
                        """),
                arguments("/v1/discovery/type-members", """
                        {
                          "repoId":"orders",
                          "expectedRevision":"1111111111111111111111111111111111111111",
                          "sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},
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

    private static MockHttpServletRequestBuilder resolveConceptRequest(String body) {
        return post("/v1/discovery/concepts/resolve")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static Stream<Arguments> resolveConceptRequests() {
        return Stream.of(
                arguments(resolveConceptTypeIdentity(), "TYPE"),
                arguments(resolveConceptIdentity("""
                        {"kind":"METHOD","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"methodName":"createOrder","parameterTypes":["com.example.Order"]}}
                        """), "METHOD"),
                arguments(resolveConceptIdentity("""
                        {"kind":"FIELD","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"name":"repository"}}
                        """), "FIELD"),
                arguments(resolveConceptIdentity("""
                        {"kind":"ANNOTATION_USAGE","declaration":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"}},"annotationType":{"status":"RESOLVED","javaType":{"packageName":"org.springframework.stereotype","className":"Service"}}}
                        """), "ANNOTATION_USAGE"),
                arguments(resolveConceptIdentity("""
                        {"kind":"TYPE_USAGE","owner":{"kind":"METHOD","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"methodName":"createOrder","parameterTypes":["com.example.Order"]}},"location":{"slot":"METHOD_PARAMETER","index":0},"path":[{"kind":"TYPE_ARGUMENT","index":0}],"referencedType":{"javaType":{"packageName":"com.example","className":"Order"},"arrayDimensions":0}}
                        """), "TYPE_USAGE"),
                arguments(resolveConceptIdentity("""
                        {"kind":"API_ROUTE","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"methodName":"createOrder","parameterTypes":["com.example.Order"]},"httpVerb":"POST","route":"/orders"}
                        """), "API_ROUTE"),
                arguments(resolveConceptIdentity("""
                        {"kind":"MQ_DESTINATION","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"methodName":"createOrder","parameterTypes":["com.example.Order"]},"broker":"KAFKA","destination":"orders"}
                        """), "MQ_DESTINATION"),
                arguments(resolveConceptIdentity("""
                        {"kind":"SCHEDULE","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"},"methodName":"createOrder","parameterTypes":["com.example.Order"]},"triggerKind":"CRON","triggerValue":"0 * * * * *"}
                        """), "SCHEDULE"),
                arguments(resolveConceptIdentity("""
                        {"kind":"MAPPER_STATEMENT","identity":{"namespace":"com.example.OrderMapper","statementId":"findOrders"}}
                        """), "MAPPER_STATEMENT"),
                arguments(resolveConceptIdentity("""
                        {"kind":"MAPPER_STATEMENT_VARIANT","identity":{"statementKey":{"namespace":"com.example.OrderMapper","statementId":"findOrders"},"resourcePath":"src/main/resources/OrderMapper.xml","databaseId":"postgres","documentOrdinal":0,"representation":"MAPPER_XML_ELEMENT"}}
                        """), "MAPPER_STATEMENT_VARIANT"));
    }

    private static Stream<Arguments> malformedResolveConceptTypeIdentities() {
        return Stream.of(
                Arguments.of("{\"kind\":\"TYPE\",\"sourceType\":{\"sourceFile\":\"/etc/passwd\",\"javaType\":{\"packageName\":\"com.example\",\"className\":\"OrderService\"}}}"),
                Arguments.of("{\"kind\":\"TYPE\",\"sourceType\":{\"sourceFile\":\"../../etc/passwd\",\"javaType\":{\"packageName\":\"com.example\",\"className\":\"OrderService\"}}}"),
                Arguments.of("{\"kind\":\"TYPE\",\"sourceType\":{\"sourceFile\":\"src\\\\main\\\\java\\\\OrderService.java\",\"javaType\":{\"packageName\":\"com.example\",\"className\":\"OrderService\"}}}"),
                Arguments.of("{\"kind\":\"TYPE\",\"sourceType\":{\"sourceFile\":\"src/main/../OrderService.java\",\"javaType\":{\"packageName\":\"com.example\",\"className\":\"OrderService\"}}}"),
                Arguments.of("{\"kind\":\"TYPE\",\"sourceType\":{\"sourceFile\":\"src/main/java/com/example/OrderService.java\",\"javaType\":{\"packageName\":\"com.example\",\"className\":\"1OrderService\"}}}"));
    }

    private static String resolveConceptTypeIdentity() {
        return resolveConceptIdentity("""
                {"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/main/java/com/example/OrderService.java"}}
                """);
    }

    private static String resolveConceptIdentity(String identity) {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":%s
                }
                """.formatted(identity);
    }

    private static RevisionBoundConceptResolution resolveConceptResult(ConceptResolveQuery query) {
        ConceptIdentity identity = query.identity();
        ConceptCatalogEntry candidate = new ConceptCatalogEntry(
                "resolve-test",
                identity,
                "display",
                "com.example",
                Optional.of("com.example.OrderService"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
        return new RevisionBoundConceptResolution(
                query.repositoryId(), ANALYZED_REVISION, candidate);
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

    private static String unavailableConceptRequest() {
        return """
                {
                  "operator":"ALL",
                  "repoId":"orders",
                  "expectedRevision":"2222222222222222222222222222222222222222",
                  "terms":[{"value":"order","matchMode":"TOKEN_PREFIX"}],
                  "kinds":["FIELD","METHOD"],
                  "packagePrefix":"com.example.narrow",
                  "offset":7,
                  "limit":25
                }
                """;
    }

    private static String withAllOperator(String body) {
        return body.contains("\"operator\"") ? body
                : body.replaceFirst("\\{", "{\n  \"operator\":\"ALL\",");
    }

    private static ConceptSearchResult conceptResult() {
        FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(new TypeMember(sourceType(), "repository"));
        MethodConceptIdentity methodIdentity = new MethodConceptIdentity(METHOD_TARGET);
        ConceptCatalogEntry field = conceptEntry(
                fieldIdentity,
                "OrderService.repository",
                Optional.of("com.example.OrderService"),
                Set.of(fieldIdentity, methodIdentity));
        ConceptCatalogEntry method = conceptEntry(
                methodIdentity,
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
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(
                        new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX),
                        new ConceptSearchTerm("service", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(ConceptKind.FIELD, ConceptKind.METHOD),
                Optional.of("com.example"),
                0,
                2);
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                query,
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

    private static ConceptSearchResult typedIdentityResult() {
        TypeConceptIdentity typeIdentity = new TypeConceptIdentity(sourceType());
        MethodConceptIdentity methodIdentity = new MethodConceptIdentity(METHOD_TARGET);
        FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(new TypeMember(sourceType(), "orders"));
        AnnotationUsageConceptIdentity annotationIdentity = new AnnotationUsageConceptIdentity(
                new ResolvedMethodDeclarationSubjectIdentity(METHOD_TARGET),
                new ResolvedAnnotationIdentity(new JavaTypeIdentity("org.springframework", "Transactional")));
        TypeUsageConceptIdentity typeUsageIdentity = new TypeUsageConceptIdentity(
                new ResolvedMethodDeclarationSubjectIdentity(METHOD_TARGET),
                new TypeUsageLocation(TypeUsageSlot.METHOD_PARAMETER, 0),
                TypeUsagePath.empty(),
                new ReferencedTypeIdentity(new JavaTypeIdentity("com.example", "Order"), 0));
        ApiRouteConceptIdentity routeIdentity = new ApiRouteConceptIdentity(
                METHOD_TARGET,
                "GET",
                "/orders");
        MqDestinationConceptIdentity destinationIdentity = new MqDestinationConceptIdentity(
                METHOD_TARGET,
                MqBroker.KAFKA,
                "orders.created");
        ScheduleConceptIdentity scheduleIdentity = new ScheduleConceptIdentity(
                METHOD_TARGET,
                ScheduleTriggerKind.CRON,
                Optional.of("0 * * * * *"));
        ConceptCatalogEntry mapper = mapperConceptResult().candidates().get(0);
        List<ConceptCatalogEntry> candidates = List.of(
                conceptEntry(typeIdentity, "OrderService", Optional.of("com.example.OrderService"), Set.of(typeIdentity)),
                conceptEntry(methodIdentity, "OrderService.createOrder", Optional.of("com.example.OrderService"), Set.of(methodIdentity)),
                fieldConceptEntry(fieldIdentity),
                conceptEntry(annotationIdentity, "Transactional on createOrder", Optional.of("com.example.OrderService"), Set.of(annotationIdentity)),
                conceptEntry(typeUsageIdentity, "Order", Optional.of("com.example.OrderService"), Set.of(typeUsageIdentity)),
                conceptEntry(routeIdentity, "GET /orders", Optional.of("com.example.OrderService"), Set.of(routeIdentity)),
                conceptEntry(destinationIdentity, "orders.created", Optional.of("com.example.OrderService"), Set.of(destinationIdentity)),
                conceptEntry(scheduleIdentity, "0 * * * * *", Optional.of("com.example.OrderService"), Set.of(scheduleIdentity)),
                mapper);
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                new ConceptSearchQuery(
                        REPOSITORY_ID,
                        ANALYZED_REVISION,
                        List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX)),
                        Set.copyOf(ACTIVE_CONCEPT_KINDS),
                        Optional.empty(),
                        0,
                        50),
                List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX)),
                ACTIVE_CONCEPT_KINDS,
                ACTIVE_CONCEPT_KINDS,
                candidates,
                new ConceptPage(0, 50, candidates.size(), candidates.size(), false),
                completeCoverage(),
                List.of(),
                Optional.empty());
    }

    private static ConceptSearchResult emptyConceptResult(
            List<SourceExtractionOutcome> extractionOutcomes) {
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX)),
                Set.of(ConceptKind.FIELD, ConceptKind.METHOD),
                Optional.of("com.example.narrow"),
                7,
                25);
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                query,
                query.terms(),
                List.of(ConceptKind.METHOD, ConceptKind.FIELD),
                List.of(ConceptKind.METHOD, ConceptKind.FIELD),
                List.of(),
                new ConceptPage(7, 25, 0, 0, false),
                extractionOutcomes,
                List.of(),
                Optional.empty());
    }

    private static ConceptSearchResult mapperConceptResult() {
        return mapperConceptResult(List.of(MAPPER_TARGET_A));
    }

    private static ConceptSearchResult mapperConceptResult(List<MethodTarget> mappedTargets) {
        MapperStatementConceptIdentity identity =
                new MapperStatementConceptIdentity(
                        new MapperStatementKey("com.example.OrderMapper", "findOrders"));
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
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "module-a/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity oracleIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "module-b/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("oracle"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        ConceptCatalogEntry mapper = new ConceptCatalogEntry(
                "test",
                identity,
                "com.example.OrderMapper#findOrders",
                "",
                Optional.of("com.example.OrderMapper"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.<ConceptIdentity>of(
                        identity,
                        new MapperStatementVariantEvidenceIdentity(oracleIdentity),
                        new MapperStatementVariantEvidenceIdentity(postgresIdentity)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(mapping),
                Optional.empty());
        return new ConceptSearchResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                new ConceptSearchQuery(
                        REPOSITORY_ID,
                        ANALYZED_REVISION,
                        List.of(new ConceptSearchTerm("findOrders", ConceptMatchMode.TOKEN_EXACT)),
                        Set.of(ConceptKind.MAPPER_STATEMENT),
                        Optional.empty(),
                        0,
                        50),
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
            String displayValue,
            Optional<String> declaringType,
            Set<ConceptIdentity> evidence) {
        return new ConceptCatalogEntry(
                "test",
                identity,
                displayValue,
                "com.example",
                declaringType,
                ConceptAuthority.SYNTAX_RESOLVED,
                evidence);
    }

    private static ConceptCatalogEntry fieldConceptEntry(FieldConceptIdentity identity) {
        NamedTypeReference map = new NamedTypeReference(
                "Map",
                "Map",
                Optional.of(new JavaTypeIdentity("java.util", "Map")),
                false);
        NamedTypeReference string = new NamedTypeReference(
                "String",
                "String",
                Optional.of(new JavaTypeIdentity("java.lang", "String")),
                false);
        NamedTypeReference list = new NamedTypeReference(
                "List",
                "List",
                Optional.of(new JavaTypeIdentity("java.util", "List")),
                false);
        NamedTypeReference order = new NamedTypeReference(
                "Order",
                "Order",
                Optional.of(new JavaTypeIdentity("com.example", "Order")),
                true);
        ParameterizedTypeReference declaredType = new ParameterizedTypeReference(
                "Map<String, List<Order[]>>",
                map,
                List.of(
                        string,
                        new ParameterizedTypeReference(
                                "List<Order[]>",
                                list,
                                List.of(new ArrayTypeReference("Order[]", order, 1)))));
        return new ConceptCatalogEntry(
                "test",
                identity,
                "OrderService.orders",
                "com.example",
                Optional.of("com.example.OrderService"),
                ConceptAuthority.SYNTAX_RESOLVED,
                Set.of(identity),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new FieldConceptDetails(declaredType)));
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
                        Optional.of(new TypeConceptIdentity(new SourceTypeIdentity(
                                new JavaTypeIdentity("com.example", "OrderRepository"),
                                "src/main/java/com/example/OrderRepository.java")))));
        TypeMemberQuery nextQuery = new TypeMemberQuery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                sourceType(),
                Set.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                2);
        DiscoveryFollowUp nextPage = followUpFactory.nextTypeMemberPage(nextQuery, 2);
        return new TypeMemberResult(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                sourceType(),
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

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity("com.example", "OrderService"), SOURCE_FILE);
    }
}
