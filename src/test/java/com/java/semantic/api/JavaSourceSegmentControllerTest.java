package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.JavaSourceSegmentApplicationService;
import com.java.semantic.syntax.application.JavaSourceSegmentQuery;
import com.java.semantic.syntax.application.JavaSourceSegmentResult;
import com.java.semantic.syntax.application.SourceRange;
import com.java.semantic.syntax.application.SourceSegmentNotFoundException;
import com.java.semantic.syntax.application.SourceSegmentTooLargeException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Java source segment HTTP 契約測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class JavaSourceSegmentControllerTest {

    private static final String REVISION = "1".repeat(40);
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderService.java";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JavaSourceSegmentApplicationService applicationService;

    @Test
    void should_return_the_bounded_exact_source_segment() throws Exception {
        SourceRange requested = new SourceRange(SOURCE_FILE, range(10, 4, 10, 10));
        SourceRange returned = new SourceRange(SOURCE_FILE, range(8, 0, 12, 1));
        given(applicationService.read(any())).willReturn(new JavaSourceSegmentResult(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                requested,
                returned,
                "submit",
                false,
                6));

        mockMvc.perform(post("/v1/discovery/java-source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.contentRange.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.contentRange.range.start.line").value(8))
                .andExpect(jsonPath("$.content").value("submit"))
                .andExpect(jsonPath("$.contextTruncated").value(false))
                .andExpect(jsonPath("$.returnedUtf8Bytes").value(6));

        ArgumentCaptor<JavaSourceSegmentQuery> query = ArgumentCaptor.forClass(JavaSourceSegmentQuery.class);
        then(applicationService).should().read(query.capture());
        assertThat(query.getValue()).isEqualTo(new JavaSourceSegmentQuery(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                requested,
                3));
    }

    @Test
    void should_reject_unknown_request_fields() throws Exception {
        mockMvc.perform(post("/v1/discovery/java-source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"contextLines\":3", "\"contextLines\":3,\"unexpected\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(applicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_map_missing_and_oversized_exact_source_to_distinct_failures() throws Exception {
        willThrow(new SourceSegmentNotFoundException()).given(applicationService).read(any());
        mockMvc.perform(post("/v1/discovery/java-source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("EXACT_CONTENT_NOT_FOUND"));

        willThrow(new SourceSegmentTooLargeException()).given(applicationService).read(any());
        mockMvc.perform(post("/v1/discovery/java-source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("SOURCE_SEGMENT_TOO_LARGE"));
    }

    private static String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "sourceRange":{
                    "sourceFile":"src/main/java/com/example/OrderService.java",
                    "range":{
                      "start":{"line":10,"character":4},
                      "end":{"line":10,"character":10}
                    }
                  },
                  "contextLines":3
                }
                """;
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
