package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.syntax.application.SourceSegmentApplicationService;
import com.java.semantic.syntax.application.SourceSegmentQuery;
import com.java.semantic.syntax.application.SourceSegmentResult;
import com.java.semantic.syntax.application.SourceSegmentNotFoundException;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** canonical source segment HTTP 契約測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class SourceSegmentControllerTest {

    private static final String REVISION = "1".repeat(40);
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderService.java";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceSegmentApplicationService applicationService;

    @Test
    void should_return_the_bounded_exact_source_segment() throws Exception {
        SourceRange requested = new SourceRange(SOURCE_FILE, range(10, 4, 10, 10));
        SourceRange returned = new SourceRange(SOURCE_FILE, range(8, 0, 12, 1));
        given(applicationService.read(any())).willReturn(new SourceSegmentResult(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                returned,
                "submit",
                Optional.empty(),
                false));

        mockMvc.perform(post("/v1/discovery/source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.segment.location.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.segment.location.range.start.line").value(8))
                .andExpect(jsonPath("$.segment.content").value("submit"))
                .andExpect(jsonPath("$.contextTruncated").value(false));

        ArgumentCaptor<SourceSegmentQuery> query = ArgumentCaptor.forClass(SourceSegmentQuery.class);
        then(applicationService).should().read(query.capture());
        assertThat(query.getValue()).isEqualTo(new SourceSegmentQuery(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                requested,
                3));
    }

    @Test
    void should_reject_unknown_request_fields() throws Exception {
        mockMvc.perform(post("/v1/discovery/source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"contextLines\":3", "\"contextLines\":3,\"unexpected\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(applicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_map_missing_source_segment_to_not_found() throws Exception {
        willThrow(new SourceSegmentNotFoundException()).given(applicationService).read(any());
        mockMvc.perform(post("/v1/discovery/source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SOURCE_SEGMENT_NOT_FOUND"));
    }

    @Test
    void should_preserve_revision_mismatch_as_conflict() throws Exception {
        willThrow(new RepositoryRevisionMismatchException(
                RepositoryRevision.ofSha(REVISION), RepositoryRevision.ofSha("2".repeat(40))))
                .given(applicationService).read(any());

        mockMvc.perform(post("/v1/discovery/source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"));
    }

    private static String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "location":{
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
