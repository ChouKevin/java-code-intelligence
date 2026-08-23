package com.java.semantic.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.NavigableSourceContextCandidate;
import com.java.semantic.syntax.application.NavigableSourceSymbolCandidate;
import com.java.semantic.syntax.application.RevisionBoundSourceSymbolResolution;
import com.java.semantic.syntax.application.SourceContextCandidateLimits;
import com.java.semantic.syntax.application.SourceMethodContextCandidate;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.application.SourceSymbolCandidate;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.application.SourceSymbolResolutionApplicationService;
import com.java.semantic.syntax.application.SourceSymbolResolutionQuery;
import com.java.semantic.syntax.application.SourceSymbolResolutionStatus;
import com.java.semantic.syntax.application.SourceTypeContextCandidate;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** source-symbol resolve endpoint 的 closed polymorphic HTTP 契約 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class SourceSymbolResolutionControllerTest {

    private static final String TOKEN = "test-token";
    private static final String SOURCE_FILE = "src/main/java/com/acme/OrderService.java";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision EXPECTED = RepositoryRevision.ofSha("1".repeat(40));
    private static final RepositoryRevision ANALYZED = RepositoryRevision.ofSha("2".repeat(40));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceSymbolResolutionApplicationService service;

    @Test
    void should_map_closed_candidate_and_context_variants_with_complete_retries() throws Exception {
        given(service.resolve(any())).willReturn(ambiguousSymbol(), ambiguousContexts());

        String symbolBody = mockMvc.perform(request(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value("2".repeat(40)))
                .andExpect(jsonPath("$.status").value("AMBIGUOUS_SYMBOL"))
                .andExpect(jsonPath("$.candidates[0].kind").value("STATIC_CONSTANT"))
                .andExpect(jsonPath("$.candidates[0].identity.scope").value("TYPE"))
                .andExpect(jsonPath("$.candidates[0].identity.ownerType.javaType.packageName")
                        .value("com.acme"))
                .andExpect(jsonPath("$.candidates[0].identity.ownerType.javaType.className")
                        .value("Outer.Inner"))
                .andExpect(jsonPath("$.candidates[0].identity.name").value("TOPIC"))
                .andExpect(jsonPath("$.candidates[0].initializerSource").value("\"orders\" + \".created\""))
                .andExpect(jsonPath("$.candidates[0].declarationRange.start.line").value(3))
                .andExpect(jsonPath("$.candidates[0].representativeOccurrence.end.character").value(17))
                .andExpect(jsonPath("$.candidates[0].occurrenceCount").value(1))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].operation")
                        .value("RESOLVE_SOURCE_SYMBOL"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/source-symbols/resolve"))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.context.sourceFile")
                        .value(SOURCE_FILE))
                .andExpect(jsonPath("$.candidates[0].availableFollowUps[0].request.position.line").value(8))
                .andReturn().getResponse().getContentAsString();
        JsonNode candidate = OBJECT_MAPPER.readTree(symbolBody).path("candidates").get(0);
        assertThat(candidate.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "kind", "identity", "declaredType", "initializerSource",
                "declarationRange", "representativeOccurrence", "occurrenceCount", "availableFollowUps");
        assertThat(OBJECT_MAPPER.readTree(symbolBody).properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "repoId", "analyzedRevision", "status", "contextCandidates",
                        "contextCandidateLimits", "candidates", "issues");

        String contextBody = mockMvc.perform(request(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextCandidateLimits.limit").value(100))
                .andExpect(jsonPath("$.contextCandidateLimits.returnedCount").value(2))
                .andExpect(jsonPath("$.contextCandidateLimits.totalCount").value(2))
                .andExpect(jsonPath("$.contextCandidateLimits.truncated").value(false))
                .andExpect(jsonPath("$.contextCandidates[0].kind").value("SOURCE_TYPE"))
                .andExpect(jsonPath("$.contextCandidates[0].sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.contextCandidates[1].kind").value("METHOD"))
                .andExpect(jsonPath("$.contextCandidates[1].target.parameterTypes[0]")
                        .value("com.acme.Order"))
                .andExpect(jsonPath("$.contextCandidates[1].retry.request.context.method.parameterTypes[0]")
                        .value("com.acme.Order"))
                .andReturn().getResponse().getContentAsString();
        JsonNode contexts = OBJECT_MAPPER.readTree(contextBody).path("contextCandidates");
        assertThat(OBJECT_MAPPER.readTree(contextBody).path("contextCandidateLimits").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("limit", "returnedCount", "totalCount", "truncated");
        assertThat(contexts.get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("kind", "sourceFile", "retry");
        assertThat(contexts.get(1).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("kind", "target", "retry");

        ArgumentCaptor<SourceSymbolResolutionQuery> query =
                ArgumentCaptor.forClass(SourceSymbolResolutionQuery.class);
        then(service).should(times(2)).resolve(query.capture());
        assertThat(query.getAllValues().getFirst().context().method()).hasValueSatisfying(method ->
                assertThat(method.parameterTypes()).hasValue(List.of("com.acme.Order")));
        assertThat(query.getAllValues().getFirst().context().javaType())
                .isEqualTo(new JavaTypeIdentity("com.acme", "Outer.Inner"));
        assertThat(query.getAllValues().getFirst().position()).hasValue(new SyntaxPosition(8, 12));
    }

    @Test
    void should_reject_unknown_malformed_and_negative_request_values() throws Exception {
        mockMvc.perform(request(validRequest().replace("\"symbol\":\"TOPIC\"", "\"symbol\":\"TOPIC\",\"extra\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(request(validRequest().replace("\"className\":\"Outer.Inner\"",
                        "\"className\":\"not a type\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(request(validRequest().replace("\"line\":8", "\"line\":-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        mockMvc.perform(request(validRequest().replace("1".repeat(40), "invalid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
    }

    private RevisionBoundSourceSymbolResolution ambiguousSymbol() {
        SourceRange declaration = new SourceRange(SOURCE_FILE, new SyntaxRange(
                new SyntaxPosition(3, 4), new SyntaxPosition(3, 55)));
        SourceRange occurrence = new SourceRange(SOURCE_FILE, new SyntaxRange(
                new SyntaxPosition(8, 12), new SyntaxPosition(8, 17)));
        SourceSymbolCandidate.StaticConstant unresolved = new SourceSymbolCandidate.StaticConstant(
                "TOPIC",
                new SourceMemberIdentity.TypeMember(
                        new SourceTypeIdentity(
                                new JavaTypeIdentity("com.acme", "Outer.Inner"), SOURCE_FILE),
                        "TOPIC"),
                "String",
                Optional.empty(),
                "\"orders\" + \".created\"",
                declaration,
                occurrence,
                1);
        DiscoveryFollowUp retry = new DiscoveryFollowUpFactory().forSourceSymbolCandidateRetry(
                REPOSITORY_ID, ANALYZED, domainQuery(), unresolved);
        NavigableSourceSymbolCandidate candidate = new NavigableSourceSymbolCandidate(unresolved, List.of(retry));
        return new RevisionBoundSourceSymbolResolution(
                REPOSITORY_ID,
                ANALYZED,
                SourceSymbolResolutionStatus.AMBIGUOUS_SYMBOL,
                List.of(),
                new SourceContextCandidateLimits(100, 0, 0, false),
                List.of(candidate),
                List.of());
    }

    private RevisionBoundSourceSymbolResolution ambiguousContexts() {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        SOURCE_FILE),
                "confirm",
                List.of("com.acme.Order"));
        DiscoveryFollowUpFactory factory = new DiscoveryFollowUpFactory();
        return new RevisionBoundSourceSymbolResolution(
                REPOSITORY_ID,
                ANALYZED,
                SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT,
                List.of(
                        new NavigableSourceContextCandidate(
                                new SourceTypeContextCandidate(SOURCE_FILE),
                                factory.forSourceTypeContextRetry(
                                        REPOSITORY_ID, ANALYZED, domainQuery(), SOURCE_FILE)),
                        new NavigableSourceContextCandidate(
                                new SourceMethodContextCandidate(target),
                                factory.forSourceMethodContextRetry(
                                        REPOSITORY_ID, ANALYZED, domainQuery(), target))),
                new SourceContextCandidateLimits(100, 2, 2, false),
                List.of(),
                List.of());
    }

    private SourceSymbolResolutionQuery domainQuery() {
        return new SourceSymbolResolutionQuery(
                REPOSITORY_ID,
                EXPECTED,
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "Outer.Inner"),
                        Optional.empty(),
                        Optional.of(new SourceSymbolContext.MethodContext(
                                "confirm", Optional.of(List.of("com.acme.Order"))))),
                "TOPIC",
                Optional.of(new SyntaxPosition(8, 12)));
    }

    private MockHttpServletRequestBuilder request(String content) {
        return post("/v1/discovery/source-symbols/resolve")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "context":{
                    "javaType":{"packageName":"com.acme","className":"Outer.Inner"},
                    "method":{"name":"confirm","parameterTypes":["com.acme.Order"]}
                  },
                  "symbol":"TOPIC",
                  "position":{"line":8,"character":12}
                }
                """;
    }
}
