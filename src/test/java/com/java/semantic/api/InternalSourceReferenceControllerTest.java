package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.InternalReferenceCacheMetadata;
import com.java.semantic.semantic.application.InternalReferenceContext;
import com.java.semantic.semantic.application.InternalReferenceGroup;
import com.java.semantic.semantic.application.InternalReferenceGroupLimits;
import com.java.semantic.semantic.application.InternalReferenceOccurrence;
import com.java.semantic.semantic.application.InternalReferencePage;
import com.java.semantic.semantic.application.InternalReferenceStatus;
import com.java.semantic.semantic.application.InternalSourceReferenceApplicationService;
import com.java.semantic.semantic.application.InternalSourceReferenceQuery;
import com.java.semantic.semantic.application.InternalSourceReferenceResult;
import com.java.semantic.semantic.application.SourceDeclarationNotFoundException;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 內部來源 reference HTTP 契約測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class InternalSourceReferenceControllerTest {

    private static final String REVISION = "1".repeat(40);
    private static final SourceTypeIdentity SOURCE_TYPE = new SourceTypeIdentity(
            new JavaTypeIdentity("com.example", "OrderService"),
            "src/main/java/com/example/OrderService.java");
    private static final MethodTarget METHOD = new MethodTarget(
            SOURCE_TYPE, "submit", List.of("com.example.SubmitOrder"));
    private static final SyntaxRange DECLARATION_RANGE = range(10, 4, 14, 5);
    private static final SyntaxRange IDENTIFIER_RANGE = range(10, 16, 10, 22);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalSourceReferenceApplicationService applicationService;

    @Test
    void should_return_composed_reference_groups_and_executable_continuations() throws Exception {
        given(applicationService.find(any())).willReturn(result());

        mockMvc.perform(post("/v1/discovery/internal-references")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.targetDeclaration.target.kind").value("METHOD"))
                .andExpect(jsonPath("$.targetDeclaration.target.identity.methodName").value("submit"))
                .andExpect(jsonPath("$.targetDeclaration.declarationRange.start.line").value(10))
                .andExpect(jsonPath("$.targetDeclaration.availableFollowUps[0].operation")
                        .value("GET_JAVA_SOURCE_SEGMENT"))
                .andExpect(jsonPath("$.referenceGroups[0].context.kind").value("METHOD"))
                .andExpect(jsonPath("$.referenceGroups[0].representativeReferences[0].range.start.line")
                        .value(30))
                .andExpect(jsonPath("$.referenceGroups[0].limits.limit").value(3))
                .andExpect(jsonPath("$.referenceGroups[0].availableFollowUps[0].operation")
                        .value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.referenceGroups[0].unavailableFollowUps").isEmpty())
                .andExpect(jsonPath("$.page.offset").value(0))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.issueSummaries").isEmpty())
                .andExpect(jsonPath("$.availableFollowUps").isEmpty());

        ArgumentCaptor<InternalSourceReferenceQuery> query =
                ArgumentCaptor.forClass(InternalSourceReferenceQuery.class);
        then(applicationService).should().find(query.capture());
        assertThat(query.getValue()).isEqualTo(new InternalSourceReferenceQuery(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                new ExactSourceDeclarationTarget.Method(METHOD),
                0,
                20));
    }

    @Test
    void should_reject_unknown_request_fields() throws Exception {
        mockMvc.perform(post("/v1/discovery/internal-references")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"limit\":20", "\"limit\":20,\"unexpected\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(applicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_map_missing_exact_declaration_to_the_typed_not_found_failure() throws Exception {
        willThrow(new SourceDeclarationNotFoundException()).given(applicationService).find(any());

        mockMvc.perform(post("/v1/discovery/internal-references")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SOURCE_DECLARATION_NOT_FOUND"));
    }

    private static InternalSourceReferenceResult result() {
        InternalReferenceGroup group = new InternalReferenceGroup(
                new InternalReferenceContext.Method(METHOD),
                List.of(new InternalReferenceOccurrence(range(30, 8, 30, 14))),
                new InternalReferenceGroupLimits(3, 1, 1, false));
        return new InternalSourceReferenceResult(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                new ExactSourceDeclaration(
                        new ExactSourceDeclarationTarget.Method(METHOD),
                        DECLARATION_RANGE,
                        IDENTIFIER_RANGE),
                InternalReferenceStatus.COMPLETE,
                1,
                List.of(group),
                new InternalReferencePage(0, 20, 1, 1, false),
                List.of(),
                new InternalReferenceCacheMetadata(false, true, 3, 12),
                1,
                1,
                1,
                1);
    }

    private static String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "target":{
                    "kind":"METHOD",
                    "identity":{
                      "sourceType":{
                        "javaType":{"packageName":"com.example","className":"OrderService"},
                        "sourceFile":"src/main/java/com/example/OrderService.java"
                      },
                      "methodName":"submit",
                      "parameterTypes":["com.example.SubmitOrder"]
                    }
                  },
                  "offset":0,
                  "limit":20
                }
                """;
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
