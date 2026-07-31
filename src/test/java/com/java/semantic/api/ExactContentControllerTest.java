package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.application.ExactContentApplicationService;
import com.java.semantic.syntax.application.ExactContentApplicationService.ContentReferenceCollisionException;
import com.java.semantic.syntax.application.ExactContentQuery;
import com.java.semantic.syntax.application.ExactContentResult;
import com.java.semantic.syntax.application.ExactContentSegment;
import com.java.semantic.syntax.application.ExactContentSegmentQuery;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** exact source 與 mapper 證據 HTTP 契約的控制器測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class ExactContentControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REQUESTED_REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final RepositoryRevision ANALYZED_REVISION = RepositoryRevision.ofSha("2".repeat(40));
    private static final MethodTarget TARGET = new MethodTarget(
            "src/main/java/com/example/OrderMapper.java",
            "com.example",
            "OrderMapper",
            "findOrders",
            List.of("java.lang.String"));
    private static final MapperStatementIdentity POSTGRES_STATEMENT = new MapperStatementIdentity(
            "com.example.OrderMapper",
            "findOrders",
            "src/main/resources/mapper/OrderMapper.xml",
            Optional.of("postgres"),
            3,
            MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    private static final MapperStatementIdentity ANNOTATION_STATEMENT = new MapperStatementIdentity(
            "com.example.OrderMapper",
            "findOrders",
            "src/main/java/com/example/OrderMapper.java",
            Optional.empty(),
            0,
            MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
    private static final MapperFragmentIdentity FRAGMENT = new MapperFragmentIdentity(
            "com.example.OrderMapper",
            "baseColumns",
            "src/main/resources/mapper/OrderMapper.xml",
            1,
            MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    private static final MapperFragmentIdentity SHARED_FRAGMENT_A = new MapperFragmentIdentity(
            "com.example.OrderMapper",
            "sharedColumns",
            "src/main/resources/mapper/OrderMapper-a.xml",
            2,
            MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    private static final MapperFragmentIdentity SHARED_FRAGMENT_Z = new MapperFragmentIdentity(
            "com.example.OrderMapper",
            "sharedColumns",
            "src/main/resources/mapper/OrderMapper-z.xml",
            4,
            MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    private static final String CONTENT_REF = "sha256:" + "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExactContentApplicationService exactContentApplicationService;

    @Test
    void should_return_exact_method_source_from_the_five_field_canonical_target() throws Exception {
        String source = "public List<Order> findOrders(String status) { return List.of(); }";
        given(exactContentApplicationService.retrieve(any())).willReturn(result(
                variant(Optional.empty(), Optional.empty(), inline(source))));

        mockMvc.perform(exactRequest("/v1/discovery/method-source", methodRequest()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value(ANALYZED_REVISION.value()))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].content").value(source))
                .andExpect(jsonPath("$.variants[0].utf8ByteCount").value(source.length()))
                .andExpect(jsonPath("$.variants[0].segmentCount").value(0))
                .andExpect(jsonPath("$.variants[0].statementIdentity").doesNotExist())
                .andExpect(jsonPath("$.variants[0].fragmentIdentity").doesNotExist())
                .andExpect(jsonPath("$.variants[0].contentRef").doesNotExist())
                .andExpect(jsonPath("$.variants[0].availableFollowUps").isEmpty());

        ArgumentCaptor<ExactContentQuery> query = ArgumentCaptor.forClass(ExactContentQuery.class);
        then(exactContentApplicationService).should().retrieve(query.capture());
        assertThat(query.getValue()).isEqualTo(
                new ExactContentQuery.MethodSource(REPOSITORY_ID, REQUESTED_REVISION, TARGET));
    }

    @Test
    void should_return_every_mapper_statement_variant_with_typed_oversize_follow_up() throws Exception {
        ExactContentResult.Content oversized = referenced(65536, 2);
        given(exactContentApplicationService.retrieve(any())).willReturn(result(
                variant(Optional.of(POSTGRES_STATEMENT), Optional.empty(), oversized),
                variant(Optional.of(ANNOTATION_STATEMENT), Optional.empty(), inline("select id from orders"))));

        mockMvc.perform(exactRequest("/v1/discovery/method-sql", methodRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].statementIdentity.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.variants[0].statementIdentity.statementId").value("findOrders"))
                .andExpect(jsonPath("$.variants[0].statementIdentity.resourcePath")
                        .value("src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.variants[0].statementIdentity.databaseId").value("postgres"))
                .andExpect(jsonPath("$.variants[0].statementIdentity.documentOrdinal").value(3))
                .andExpect(jsonPath("$.variants[0].statementIdentity.representation")
                        .value("MAPPER_XML_ELEMENT"))
                .andExpect(jsonPath("$.variants[0].content").doesNotExist())
                .andExpect(jsonPath("$.variants[0].contentRef").value(CONTENT_REF))
                .andExpect(jsonPath("$.variants[0].utf8ByteCount").value(65536))
                .andExpect(jsonPath("$.variants[0].segmentCount").value(2))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SQL_SEGMENT"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-sql-segment"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].api.operationId")
                        .value("getMethodSqlSegment"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].request.target.sourceFile")
                        .value(TARGET.sourceFile()))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].request.contentRef")
                        .value(CONTENT_REF))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].request.segmentIndex").value(0))
                .andExpect(jsonPath("$.variants[1].statementIdentity.databaseId").doesNotExist())
                .andExpect(jsonPath("$.variants[1].statementIdentity.representation")
                        .value("ANNOTATION_SQL_TEXT"))
                .andExpect(jsonPath("$.variants[1].content").value("select id from orders"));

        ArgumentCaptor<ExactContentQuery> query = ArgumentCaptor.forClass(ExactContentQuery.class);
        then(exactContentApplicationService).should().retrieve(query.capture());
        assertThat(query.getValue()).isEqualTo(
                new ExactContentQuery.MapperStatement(REPOSITORY_ID, REQUESTED_REVISION, TARGET));
    }

    @Test
    void should_project_every_typed_mapper_include_resolution_as_executable_fragment_follow_ups()
            throws Exception {
        List<ExactContentResult.IncludeResolution> includeResolutions = List.of(
                new ExactContentResult.IncludeResolution(
                        "baseColumns",
                        ExactContentResult.IncludeResolutionStatus.RESOLVED,
                        List.of(FRAGMENT)),
                new ExactContentResult.IncludeResolution(
                        "sharedColumns",
                        ExactContentResult.IncludeResolutionStatus.AMBIGUOUS,
                        List.of(SHARED_FRAGMENT_A, SHARED_FRAGMENT_Z)),
                new ExactContentResult.IncludeResolution(
                        "missingColumns",
                        ExactContentResult.IncludeResolutionStatus.UNRESOLVED,
                        List.of()));
        given(exactContentApplicationService.retrieve(any())).willReturn(result(
                variant(
                        Optional.of(POSTGRES_STATEMENT),
                        Optional.empty(),
                        includeResolutions,
                        inline("<select id=\"findOrders\"/>"))));

        mockMvc.perform(exactRequest("/v1/discovery/method-sql", methodRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].includeResolutions.length()").value(3))
                .andExpect(jsonPath("$.variants[0].includeResolutions[0].refId")
                        .value("baseColumns"))
                .andExpect(jsonPath("$.variants[0].includeResolutions[0].status")
                        .value("RESOLVED"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].operation")
                        .value("GET_MAPPER_FRAGMENT"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].api.method")
                        .value("POST"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/mapper-sql-fragment"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].api.operationId")
                        .value("getMapperFragment"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.repoId")
                        .value(REPOSITORY_ID.value()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.expectedRevision")
                        .value(REQUESTED_REVISION.value()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.fragmentIdentity.namespace")
                        .value(FRAGMENT.namespace()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.fragmentIdentity.fragmentId")
                        .value(FRAGMENT.fragmentId()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.fragmentIdentity.resourcePath")
                        .value(FRAGMENT.resourcePath()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.fragmentIdentity.documentOrdinal")
                        .value(FRAGMENT.documentOrdinal()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[0].availableFollowUps[0].request.fragmentIdentity.representation")
                        .value(FRAGMENT.representation().name()))
                .andExpect(jsonPath("$.variants[0].includeResolutions[1].status")
                        .value("AMBIGUOUS"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[1].availableFollowUps.length()")
                        .value(2))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[1].availableFollowUps[0].request.fragmentIdentity.resourcePath")
                        .value(SHARED_FRAGMENT_A.resourcePath()))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[1].availableFollowUps[1].request.fragmentIdentity.resourcePath")
                        .value(SHARED_FRAGMENT_Z.resourcePath()))
                .andExpect(jsonPath("$.variants[0].includeResolutions[2].status")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath(
                        "$.variants[0].includeResolutions[2].availableFollowUps")
                        .isEmpty());
    }

    @Test
    void should_return_exact_mapper_fragment_with_its_stateless_segment_follow_up() throws Exception {
        given(exactContentApplicationService.retrieve(any())).willReturn(result(
                variant(Optional.empty(), Optional.of(FRAGMENT), referenced(40000, 2))));

        mockMvc.perform(exactRequest("/v1/discovery/mapper-sql-fragment", fragmentRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].fragmentIdentity.namespace")
                        .value("com.example.OrderMapper"))
                .andExpect(jsonPath("$.variants[0].fragmentIdentity.fragmentId").value("baseColumns"))
                .andExpect(jsonPath("$.variants[0].fragmentIdentity.resourcePath")
                        .value("src/main/resources/mapper/OrderMapper.xml"))
                .andExpect(jsonPath("$.variants[0].fragmentIdentity.documentOrdinal").value(1))
                .andExpect(jsonPath("$.variants[0].fragmentIdentity.representation")
                        .value("MAPPER_XML_ELEMENT"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].operation")
                        .value("GET_MAPPER_FRAGMENT_SEGMENT"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/mapper-fragment-segment"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].request.fragmentIdentity.fragmentId")
                        .value("baseColumns"))
                .andExpect(jsonPath("$.variants[0].availableFollowUps[0].request.contentRef")
                        .value(CONTENT_REF));

        ArgumentCaptor<ExactContentQuery> query = ArgumentCaptor.forClass(ExactContentQuery.class);
        then(exactContentApplicationService).should().retrieve(query.capture());
        assertThat(query.getValue()).isEqualTo(
                new ExactContentQuery.MapperFragment(REPOSITORY_ID, REQUESTED_REVISION, FRAGMENT));
    }

    @Test
    void should_reconstruct_each_original_exact_authority_for_stateless_segment_reads() throws Exception {
        ExactContentSegment nextSegment = new ExactContentSegment(
                CONTENT_REF,
                0,
                2,
                4,
                "🙂",
                Optional.of(new ExactContentSegmentQuery(
                        new ExactContentQuery.MethodSource(REPOSITORY_ID, REQUESTED_REVISION, TARGET),
                        CONTENT_REF,
                        1)));
        given(exactContentApplicationService.readSegment(any())).willReturn(nextSegment);

        mockMvc.perform(exactRequest(
                        "/v1/discovery/method-source-segment",
                        methodSegmentRequest(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("🙂"))
                .andExpect(jsonPath("$.utf8ByteCount").value(4))
                .andExpect(jsonPath("$.nextSegmentFollowUp.operation")
                        .value("GET_METHOD_SOURCE_SEGMENT"))
                .andExpect(jsonPath("$.nextSegmentFollowUp.request.segmentIndex").value(1));
        mockMvc.perform(exactRequest(
                        "/v1/discovery/method-sql-segment",
                        methodSegmentRequest(0)))
                .andExpect(status().isOk());
        mockMvc.perform(exactRequest(
                        "/v1/discovery/mapper-fragment-segment",
                        fragmentSegmentRequest(0)))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ExactContentSegmentQuery> query =
                ArgumentCaptor.forClass(ExactContentSegmentQuery.class);
        then(exactContentApplicationService).should(times(3)).readSegment(query.capture());
        assertThat(query.getAllValues()).containsExactly(
                new ExactContentSegmentQuery(
                        new ExactContentQuery.MethodSource(REPOSITORY_ID, REQUESTED_REVISION, TARGET),
                        CONTENT_REF,
                        0),
                new ExactContentSegmentQuery(
                        new ExactContentQuery.MapperStatement(REPOSITORY_ID, REQUESTED_REVISION, TARGET),
                        CONTENT_REF,
                        0),
                new ExactContentSegmentQuery(
                        new ExactContentQuery.MapperFragment(REPOSITORY_ID, REQUESTED_REVISION, FRAGMENT),
                        CONTENT_REF,
                        0));
    }

    @Test
    void should_reject_malformed_unknown_and_invalid_segment_requests_without_calling_the_service() throws Exception {
        mockMvc.perform(exactRequest("/v1/discovery/method-source", """
                {
                  "repoId":"orders",
                  "expectedRevision":"invalid",
                  "target":{
                    "sourceFile":"src/main/java/com/example/OrderMapper.java",
                    "packageName":"com.example",
                    "className":"OrderMapper",
                    "methodName":"findOrders",
                    "parameterTypes":["java.lang.String"]
                  },
                  "sourceFile":"arbitrary.java"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(exactRequest(
                        "/v1/discovery/method-source-segment",
                        methodSegmentRequest(-1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(exactRequest("/v1/discovery/mapper-sql-fragment", "{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(exactContentApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_preserve_conflicts_and_map_exact_not_found_to_a_sanitized_404() throws Exception {
        willThrow(new RepositoryRevisionMismatchException(REQUESTED_REVISION, ANALYZED_REVISION))
                .given(exactContentApplicationService).retrieve(any());
        mockMvc.perform(exactRequest("/v1/discovery/method-source", methodRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"));

        willThrow(new SemanticBindingAmbiguousException(TARGET, List.of(
                alternateTarget("z/OrderMapper.java", "ZOrderMapper"),
                alternateTarget("a/OrderMapper.java", "AOrderMapper"))))
                .given(exactContentApplicationService).retrieve(any());
        mockMvc.perform(exactRequest("/v1/discovery/method-source", methodRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_BINDING_AMBIGUOUS"))
                .andExpect(jsonPath("$.candidates[0].sourceFile").value("a/OrderMapper.java"))
                .andExpect(jsonPath("$.candidates[1].sourceFile").value("z/OrderMapper.java"));

        willThrow(new SemanticTargetNotFoundException(TARGET))
                .given(exactContentApplicationService).retrieve(any());
        mockMvc.perform(exactRequest("/v1/discovery/method-source", methodRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("EXACT_CONTENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("exact content was not found"))
                .andExpect(jsonPath("$.target").doesNotExist())
                .andExpect(jsonPath("$.candidates").isEmpty());
    }

    @Test
    void should_sanitize_internal_content_reference_collisions() throws Exception {
        ContentReferenceCollisionException collision = contentReferenceCollision();
        willThrow(collision).given(exactContentApplicationService).retrieve(any());

        String response = mockMvc.perform(exactRequest("/v1/discovery/method-source", methodRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("request failed"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("collision")
                .doesNotContain(CONTENT_REF)
                .doesNotContain(TARGET.sourceFile());
        then(exactContentApplicationService).should().retrieve(any());
    }

    private MockHttpServletRequestBuilder exactRequest(String path, String body) {
        return post(path)
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String methodRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "target":{
                    "sourceFile":"src/main/java/com/example/OrderMapper.java",
                    "packageName":"com.example",
                    "className":"OrderMapper",
                    "methodName":"findOrders",
                    "parameterTypes":["java.lang.String"]
                  }
                }
                """;
    }

    private static String methodSegmentRequest(int segmentIndex) {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "target":{
                    "sourceFile":"src/main/java/com/example/OrderMapper.java",
                    "packageName":"com.example",
                    "className":"OrderMapper",
                    "methodName":"findOrders",
                    "parameterTypes":["java.lang.String"]
                  },
                  "contentRef":"%s",
                  "segmentIndex":%d
                }
                """.formatted(CONTENT_REF, segmentIndex);
    }

    private static String fragmentRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "fragmentIdentity":{
                    "namespace":"com.example.OrderMapper",
                    "fragmentId":"baseColumns",
                    "resourcePath":"src/main/resources/mapper/OrderMapper.xml",
                    "documentOrdinal":1,
                    "representation":"MAPPER_XML_ELEMENT"
                  }
                }
                """;
    }

    private static String fragmentSegmentRequest(int segmentIndex) {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "fragmentIdentity":{
                    "namespace":"com.example.OrderMapper",
                    "fragmentId":"baseColumns",
                    "resourcePath":"src/main/resources/mapper/OrderMapper.xml",
                    "documentOrdinal":1,
                    "representation":"MAPPER_XML_ELEMENT"
                  },
                  "contentRef":"%s",
                  "segmentIndex":%d
                }
                """.formatted(CONTENT_REF, segmentIndex);
    }

    private static ExactContentResult result(ExactContentResult.ContentVariant... variants) {
        return new ExactContentResult(REPOSITORY_ID, ANALYZED_REVISION, List.of(variants));
    }

    private static ExactContentResult.ContentVariant variant(
            Optional<MapperStatementIdentity> statementIdentity,
            Optional<MapperFragmentIdentity> fragmentIdentity,
            ExactContentResult.Content content) {
        return new ExactContentResult.ContentVariant(statementIdentity, fragmentIdentity, content);
    }

    private static ExactContentResult.ContentVariant variant(
            Optional<MapperStatementIdentity> statementIdentity,
            Optional<MapperFragmentIdentity> fragmentIdentity,
            List<ExactContentResult.IncludeResolution> includeResolutions,
            ExactContentResult.Content content) {
        return new ExactContentResult.ContentVariant(
                statementIdentity,
                fragmentIdentity,
                includeResolutions,
                content);
    }

    private static ExactContentResult.Content inline(String content) {
        return new ExactContentResult.Content(
                Optional.of(content),
                Optional.empty(),
                content.getBytes(StandardCharsets.UTF_8).length,
                0);
    }

    private static ExactContentResult.Content referenced(int utf8ByteCount, int segmentCount) {
        return new ExactContentResult.Content(
                Optional.empty(),
                Optional.of(CONTENT_REF),
                utf8ByteCount,
                segmentCount);
    }

    private static MethodTarget alternateTarget(String sourceFile, String className) {
        return new MethodTarget(
                sourceFile,
                "com.example",
                className,
                TARGET.methodName(),
                TARGET.parameterTypes());
    }

    private static ContentReferenceCollisionException contentReferenceCollision() throws Exception {
        Constructor<ContentReferenceCollisionException> constructor =
                ContentReferenceCollisionException.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(CONTENT_REF);
    }
}
