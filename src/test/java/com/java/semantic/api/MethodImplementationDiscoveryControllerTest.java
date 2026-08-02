package com.java.semantic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.callgraph.application.ImplementationCandidate;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.semantic.application.ImplementationDiscoveryContractException;
import com.java.semantic.semantic.application.ImplementationTargetUnsupportedException;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryApplicationService;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryQuery;
import com.java.semantic.semantic.application.MethodImplementationIssueReason;
import com.java.semantic.semantic.application.MethodImplementationLimits;
import com.java.semantic.semantic.application.RevisionBoundMethodImplementations;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 方法實作探索 HTTP 契約的控制器測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class MethodImplementationDiscoveryControllerTest {

    private static final String TOKEN = "test-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REQUESTED_REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final RepositoryRevision ANALYZED_REVISION = RepositoryRevision.ofSha("2".repeat(40));
    private static final MethodTarget DECLARATION_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderHandler"),
                        "src/main/java/com/example/OrderHandler.java"),
                "handle",
                List.of("com.example.Order"));
    private static final MethodTarget IMPLEMENTATION_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "DefaultOrderHandler"),
                        "src/main/java/com/example/DefaultOrderHandler.java"),
                "handle",
                List.of("com.example.Order"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MethodImplementationDiscoveryApplicationService methodImplementationDiscoveryApplicationService;

    @Test
    void should_return_the_closed_authoritative_method_implementation_discovery_response() throws Exception {
        given(methodImplementationDiscoveryApplicationService.discover(any())).willReturn(discoveryResult());

        String body = mockMvc.perform(post("/v1/discovery/method-implementations")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "declarationTarget":{
                                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                                    "methodName":"handle",
                                    "parameterTypes":["com.example.Order"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.revision").value("2222222222222222222222222222222222222222"))
                .andExpect(jsonPath("$.requestedTarget.sourceType.sourceFile").value(DECLARATION_TARGET.sourceFile()))
                .andExpect(jsonPath("$.candidates[0].target.sourceType.sourceFile").value(IMPLEMENTATION_TARGET.sourceFile()))
                .andExpect(jsonPath("$.candidates[0].primary").value(true))
                .andExpect(jsonPath("$.candidates[0].qualifiers[0]").value("ordersHandler"))
                .andExpect(jsonPath("$.candidates[0].profiles[0]").value("prod"))
                .andExpect(jsonPath("$.limits.limit").value(100))
                .andExpect(jsonPath("$.limits.returnedCount").value(1))
                .andExpect(jsonPath("$.limits.totalCount").value(2))
                .andExpect(jsonPath("$.limits.truncated").value(true))
                .andExpect(jsonPath("$.resolution.status").value("PARTIAL"))
                .andExpect(jsonPath("$.resolution.issueSummaries[0].code").value("LOCAL_CONVERSION_FAILED"))
                .andExpect(jsonPath("$.resolution.issueSummaries[0].count").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = OBJECT_MAPPER.readTree(body);
        assertThat(response).isEqualTo(OBJECT_MAPPER.readTree("""
                {
                  "repoId":"orders",
                  "revision":"2222222222222222222222222222222222222222",
                  "requestedTarget":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                    "methodName":"handle",
                    "parameterTypes":["com.example.Order"]
                  },
                  "candidates":[{
                    "target":{
                      "sourceType":{"javaType":{"packageName":"com.example","className":"DefaultOrderHandler"},"sourceFile":"src/main/java/com/example/DefaultOrderHandler.java"},
                      "methodName":"handle",
                      "parameterTypes":["com.example.Order"]
                    },
                    "primary":true,
                    "qualifiers":["ordersHandler"],
                    "profiles":["prod"]
                  }],
                  "limits":{"limit":100,"returnedCount":1,"totalCount":2,"truncated":true},
                  "resolution":{
                    "status":"PARTIAL",
                    "issueSummaries":[
                      {"code":"LOCAL_CONVERSION_FAILED","count":2},
                      {"code":"EXTERNAL_TARGET","count":1}
                    ]
                  }
                }
                """));

        ArgumentCaptor<MethodImplementationDiscoveryQuery> query =
                ArgumentCaptor.forClass(MethodImplementationDiscoveryQuery.class);
        then(methodImplementationDiscoveryApplicationService).should().discover(query.capture());
        assertThat(query.getValue()).isEqualTo(new MethodImplementationDiscoveryQuery(
                REPOSITORY_ID, REQUESTED_REVISION, DECLARATION_TARGET));
    }

    @Test
    void should_reject_missing_invalid_and_unknown_method_implementation_discovery_request_fields() throws Exception {
        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"invalid",
                  "declarationTarget":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                    "methodName":"handle",
                    "parameterTypes":[]
                  }
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "unexpected":true,
                  "declarationTarget":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                    "methodName":"handle",
                    "parameterTypes":[]
                  }
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "declarationTarget":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                    "methodName":"handle",
                    "parameterTypes":[],
                    "unexpected":true
                  }
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(methodImplementationDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_preserve_repository_and_semantic_failure_envelopes() throws Exception {
        willThrow(new RepositoryNotReadyException(REPOSITORY_ID))
                .given(methodImplementationDiscoveryApplicationService).discover(any());
        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_NOT_READY"));

        willThrow(new RepositoryRevisionMismatchException(REQUESTED_REVISION, ANALYZED_REVISION))
                .given(methodImplementationDiscoveryApplicationService).discover(any());
        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"));

        willThrow(new SemanticBindingUnresolvedException(DECLARATION_TARGET))
                .given(methodImplementationDiscoveryApplicationService).discover(any());
        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_BINDING_UNRESOLVED"));

        willThrow(new SemanticTargetNotFoundException(DECLARATION_TARGET))
                .given(methodImplementationDiscoveryApplicationService).discover(any());
        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_TARGET_NOT_FOUND"));

        willThrow(new SemanticProtocolException())
                .given(methodImplementationDiscoveryApplicationService).discover(any());
        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_PROTOCOL_ERROR"));
    }

    @Test
    void should_return_the_unsupported_target_as_a_typed_unprocessable_error() throws Exception {
        willThrow(new ImplementationTargetUnsupportedException(DECLARATION_TARGET))
                .given(methodImplementationDiscoveryApplicationService).discover(any());

        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IMPLEMENTATION_TARGET_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value("requested method does not support implementation discovery"))
                .andExpect(jsonPath("$.target.sourceType.sourceFile").value(DECLARATION_TARGET.sourceFile()));
    }

    @Test
    void should_sanitize_unexpected_implementation_discovery_metadata_contract_failures() throws Exception {
        willThrow(new ImplementationDiscoveryContractException(IMPLEMENTATION_TARGET))
                .given(methodImplementationDiscoveryApplicationService).discover(any());

        mockMvc.perform(discoveryRequest(validRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("request failed"));
    }

    @Test
    void should_group_issues_in_enum_order_and_keep_truncation_independent_from_resolution_status() {
        RevisionBoundMethodImplementations discovery = new RevisionBoundMethodImplementations(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                DECLARATION_TARGET,
                List.of(),
                new MethodImplementationLimits(100, 0, 1, true),
                List.of(
                        MethodImplementationIssueReason.NON_EXECUTABLE_TARGET,
                        MethodImplementationIssueReason.EXTERNAL_TARGET,
                        MethodImplementationIssueReason.EXTERNAL_TARGET));

        MethodImplementationDiscoveryResponseMapper mapper = new MethodImplementationDiscoveryResponseMapper();

        assertThat(mapper.toResponse(discovery).resolution().status().name()).isEqualTo("COMPLETE");
        assertThat(mapper.toResponse(discovery).resolution().issueSummaries())
                .extracting(summary -> summary.code() + ":" + summary.count())
                .containsExactly("EXTERNAL_TARGET:2", "NON_EXECUTABLE_TARGET:1");
        assertThat(mapper.toResponse(discovery).limits().truncated()).isTrue();
    }

    private static MockHttpServletRequestBuilder discoveryRequest(String body) {
        return post("/v1/discovery/method-implementations")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "declarationTarget":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderHandler"},"sourceFile":"src/main/java/com/example/OrderHandler.java"},
                    "methodName":"handle",
                    "parameterTypes":["com.example.Order"]
                  }
                }
                """;
    }

    private static RevisionBoundMethodImplementations discoveryResult() {
        SemanticPosition position = new SemanticPosition(0, 0);
        SemanticRange range = new SemanticRange(position, position);
        SemanticMethod method = new SemanticMethod(
                IMPLEMENTATION_TARGET.packageName(),
                IMPLEMENTATION_TARGET.className(),
                IMPLEMENTATION_TARGET.methodName(),
                IMPLEMENTATION_TARGET.parameterTypes(),
                "void",
                new SemanticLocation("file:///DefaultOrderHandler.java", range, range));
        ImplementationCandidate candidate = new ImplementationCandidate(
                method,
                IMPLEMENTATION_TARGET,
                true,
                List.of("ordersHandler"),
                List.of("prod"));
        return new RevisionBoundMethodImplementations(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                DECLARATION_TARGET,
                List.of(candidate),
                new MethodImplementationLimits(100, 1, 2, true),
                List.of(
                        MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED,
                        MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED,
                        MethodImplementationIssueReason.EXTERNAL_TARGET));
    }
}
