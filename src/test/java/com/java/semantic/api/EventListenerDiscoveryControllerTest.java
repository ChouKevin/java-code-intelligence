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
import com.java.semantic.syntax.application.AnnotationMatchKind;
import com.java.semantic.syntax.application.CandidatePage;
import com.java.semantic.syntax.application.EventListenerCandidate;
import com.java.semantic.syntax.application.EventListenerDiscoveryApplicationService;
import com.java.semantic.syntax.application.EventListenerDiscoveryContractException;
import com.java.semantic.syntax.application.EventListenerDiscoveryPage;
import com.java.semantic.syntax.application.EventListenerDiscoveryQuery;
import com.java.semantic.syntax.application.ListenerAnnotationEvidence;
import com.java.semantic.syntax.application.ListenerAnnotationKind;
import com.java.semantic.syntax.application.ListenerObservationCode;
import com.java.semantic.syntax.application.ListenerObservationSummary;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import com.java.semantic.syntax.application.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class EventListenerDiscoveryControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REQUESTED_REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final RepositoryRevision ANALYZED_REVISION = RepositoryRevision.ofSha("2".repeat(40));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventListenerDiscoveryApplicationService eventListenerDiscoveryApplicationService;

    @Test
    void should_return_closed_authoritative_listener_discovery_response() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willReturn(discoveryResult());

        String body = mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "eventType":"com.example.Requested",
                  "expectedRevision":"1111111111111111111111111111111111111111"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value("2222222222222222222222222222222222222222"))
                .andExpect(jsonPath("$.requestedEventType").value("com.example.Authoritative"))
                .andExpect(jsonPath("$.candidates[0].target.sourceType.sourceFile")
                        .value("src/main/java/com/example/OrderListener.java"))
                .andExpect(jsonPath("$.candidates[0].target.sourceType.javaType.packageName")
                        .value("com.example"))
                .andExpect(jsonPath("$.candidates[0].target.sourceType.javaType.className")
                        .value("OrderListener"))
                .andExpect(jsonPath("$.candidates[0].target.methodName").value("onOrder"))
                .andExpect(jsonPath("$.candidates[0].target.parameterTypes[0]").value("com.example.Order"))
                .andExpect(jsonPath("$.candidates[0].sourceRange.sourceFile").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].sourceRange.start.line").value(10))
                .andExpect(jsonPath("$.candidates[0].listenerAnnotations[0].kind").value("EVENT_LISTENER"))
                .andExpect(jsonPath("$.candidates[0].listenerAnnotations[0].matchKind")
                        .value("RESOLVED_IDENTITY"))
                .andExpect(jsonPath("$.page.returnedCount").value(1))
                .andExpect(jsonPath("$.page.totalCount").value(3))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.observationSummaries[0].code").value("LISTENER_TARGET_UNRESOLVED"))
                .andExpect(jsonPath("$.observationSummaries[0].samples[0].sourceFile")
                        .value("src/main/java/com/example/BrokenListener.java"))
                .andExpect(jsonPath("$.observationSummaries[0].samples[0].range.start.line").value(4))
                .andExpect(jsonPath("$.observationSummaries[0].sourceRangeSamples").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = OBJECT_MAPPER.readTree(body);
        assertThat(response.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "repoId", "analyzedRevision", "requestedEventType", "candidates", "page", "observationSummaries");
        assertThat(response.path("candidates").get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("target", "listenerAnnotations", "sourceRange");
        assertThat(response.path("page").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("offset", "limit", "returnedCount", "totalCount", "hasMore");
        assertThat(response.path("observationSummaries").get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "totalCount", "samples");

        ArgumentCaptor<EventListenerDiscoveryQuery> query = ArgumentCaptor.forClass(EventListenerDiscoveryQuery.class);
        then(eventListenerDiscoveryApplicationService).should().discover(query.capture());
        assertThat(query.getValue()).isEqualTo(new EventListenerDiscoveryQuery(
                REPOSITORY_ID, REQUESTED_REVISION, "com.example.Requested", 0, 50));
    }

    @Test
    void should_reject_unknown_discovery_request_property() throws Exception {
        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "eventType":"com.example.OrderCreated",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "unexpected":true
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_missing_repository_id() throws Exception {
        mockMvc.perform(discoveryRequest("""
                {
                  "eventType":"com.example.OrderCreated",
                  "expectedRevision":"1111111111111111111111111111111111111111"
                }
                """))
                .andExpect(status().isBadRequest());

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example.OrderCreated",
            "com.example.Outer.Inner",
            "com.example.OrderCreated[]",
            "com.example.OrderCreated[][]"
    })
    void should_accept_supported_event_type_spellings(String eventType) throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willReturn(noMatchResult());

        mockMvc.perform(discoveryRequest(request(eventType, REQUESTED_REVISION.value(), 0, 50)))
                .andExpect(status().isOk());

        then(eventListenerDiscoveryApplicationService).should().discover(new EventListenerDiscoveryQuery(
                REPOSITORY_ID, REQUESTED_REVISION, eventType, 0, 50));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderCreated", "com.example.Order$Created", "com.example.", "com..example.OrderCreated"})
    void should_reject_unsupported_event_type_spellings(String eventType) throws Exception {
        mockMvc.perform(discoveryRequest(request(eventType, REQUESTED_REVISION.value(), 0, 50)))
                .andExpect(status().isBadRequest());

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_invalid_expected_revision() throws Exception {
        mockMvc.perform(discoveryRequest(request("com.example.OrderCreated", "invalid", 0, 50)))
                .andExpect(status().isBadRequest());

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_negative_offset() throws Exception {
        mockMvc.perform(discoveryRequest(request("com.example.OrderCreated", REQUESTED_REVISION.value(), -1, 50)))
                .andExpect(status().isBadRequest());

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void should_reject_limit_outside_discovery_bounds(int limit) throws Exception {
        mockMvc.perform(discoveryRequest(request("com.example.OrderCreated", REQUESTED_REVISION.value(), 0, limit)))
                .andExpect(status().isBadRequest());

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_return_empty_page_when_no_listener_matches() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willReturn(noMatchResult());

        mockMvc.perform(discoveryRequest(request("com.example.OrderCreated", REQUESTED_REVISION.value(), 0, 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.hasMore").value(false));
    }

    @Test
    void should_default_omitted_pagination_before_listener_discovery() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willReturn(noMatchResult());

        mockMvc.perform(discoveryRequest("""
                {
                  "repoId":"orders",
                  "eventType":"com.example.OrderCreated",
                  "expectedRevision":"1111111111111111111111111111111111111111"
                }
                """))
                .andExpect(status().isOk());

        then(eventListenerDiscoveryApplicationService).should().discover(new EventListenerDiscoveryQuery(
                REPOSITORY_ID,
                REQUESTED_REVISION,
                "com.example.OrderCreated",
                0,
                50));
    }

    @Test
    void should_return_revision_mismatch_as_conflict() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willThrow(
                new RepositoryRevisionMismatchException(REQUESTED_REVISION, ANALYZED_REVISION));

        mockMvc.perform(discoveryRequest(request("com.example.OrderCreated", REQUESTED_REVISION.value(), 0, 50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"));
    }

    @Test
    void should_return_a_sanitized_internal_error_for_listener_source_identity_contract_failure() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willThrow(
                new EventListenerDiscoveryContractException(
                        "src/main/java/com/example/Metadata.java",
                        "src/main/java/com/example/Target.java"));

        mockMvc.perform(discoveryRequest(request(
                "com.example.OrderCreated", REQUESTED_REVISION.value(), 0, 50)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("request failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset", "limit"})
    void should_reject_explicit_null_pagination_property_before_listener_discovery(String nullableProperty)
            throws Exception {
        String body = """
                {
                  "repoId":"orders",
                  "eventType":"com.example.OrderCreated",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "%s":null
                }
                """.formatted(nullableProperty);

        mockMvc.perform(discoveryRequest(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(eventListenerDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    private static MockHttpServletRequestBuilder discoveryRequest(String body) {
        return post("/v1/discovery/event-listeners")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String request(String eventType, String revision, int offset, int limit) {
        return """
                {
                  "repoId":"orders",
                  "eventType":"%s",
                  "expectedRevision":"%s",
                  "offset":%d,
                  "limit":%d
                }
                """.formatted(eventType, revision, offset, limit);
    }

    private static RevisionBoundEventListenerDiscovery discoveryResult() {
        String sourceFile = "src/main/java/com/example/OrderListener.java";
        EventListenerCandidate candidate = new EventListenerCandidate(
                new MethodTarget(
                        new SourceTypeIdentity(
                                new JavaTypeIdentity("com.example", "OrderListener"),
                                sourceFile),
                        "onOrder",
                        List.of("com.example.Order")),
                new SourceRange(sourceFile, new SyntaxRange(
                        new SyntaxPosition(10, 4), new SyntaxPosition(12, 5))),
                List.of(new ListenerAnnotationEvidence(
                        ListenerAnnotationKind.EVENT_LISTENER, AnnotationMatchKind.RESOLVED_IDENTITY)));
        ListenerObservationSummary observation = new ListenerObservationSummary(
                ListenerObservationCode.LISTENER_TARGET_UNRESOLVED,
                2,
                List.of(new SourceRange(
                        "src/main/java/com/example/BrokenListener.java",
                        new SyntaxRange(new SyntaxPosition(4, 0), new SyntaxPosition(4, 18)))));
        return new RevisionBoundEventListenerDiscovery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                "com.example.Authoritative",
                new EventListenerDiscoveryPage(
                        new CandidatePage(List.of(candidate), 0, 50, 1, 3, true),
                        List.of(observation)));
    }

    private static RevisionBoundEventListenerDiscovery noMatchResult() {
        return new RevisionBoundEventListenerDiscovery(
                REPOSITORY_ID,
                ANALYZED_REVISION,
                "com.example.OrderCreated",
                EventListenerDiscoveryPage.empty(0, 50));
    }
}
