package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.StrictMcpToolInputDecoder;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.dto.repository.RepositoryMcpDtos;
import com.java.semantic.mcp.dto.route.ApiRouteMcpDtos;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.trie.ApiRouteApplicationService;
import com.java.semantic.trie.ApiRouteMatchBatch;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/** 驗證 core query provider 的 MCP 邊界契約 */
@SpringBootTest
class CoreQueryMcpToolsTest {

    @Autowired
    private RepositoryMcpTools repositoryTools;

    @Autowired
    private CallGraphMcpTools callGraphTools;

    @Autowired
    private ApiRouteMcpTools routeTools;

    @MockitoBean
    private ApiRouteApplicationService apiRouteApplicationService;

    @Test
    void should_register_the_seven_core_queries_with_revision_pinned_content_inputs() {
        assertThat(names(repositoryTools.registrations())).containsExactly(
                "semantic_list_repositories", "semantic_get_repository", "semantic_list_entry_points");
        assertThat(names(callGraphTools.registrations())).containsExactly(
                "semantic_analyze_outgoing_call_graph", "semantic_analyze_incoming_call_graph");
        assertThat(names(routeTools.registrations())).containsExactly(
                "semantic_lookup_api_routes", "semantic_suggest_api_routes");

        assertThat(RepositoryMcpDtos.EntryPointsInput.class.getRecordComponents())
                .extracting(RecordComponent::getName).contains("repoId", "expectedRevision");
        assertThat(CallGraphMcpDtos.Input.class.getRecordComponents())
                .extracting(RecordComponent::getName).contains("repoId", "expectedRevision", "target");
        assertThat(ApiRouteMcpDtos.LookupInput.class.getRecordComponents())
                .extracting(RecordComponent::getName).contains("repoId", "expectedRevision");
        assertThat(ApiRouteMcpDtos.SuggestInput.class.getRecordComponents())
                .extracting(RecordComponent::getName).contains("repoId", "expectedRevision", "limit");
    }

    @Test
    void should_invoke_method_agnostic_route_queries_when_http_method_is_omitted() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StrictMcpToolInputDecoder decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);
        ApiRouteMatchBatch result = mock(ApiRouteMatchBatch.class);
        given(apiRouteApplicationService.lookupMatches(any(), any(), any(), any())).willReturn(result);
        given(apiRouteApplicationService.suggestMatches(any(), any(), any(), any(), anyInt())).willReturn(result);

        ApiRouteMcpDtos.LookupInput lookup = decoder.decode(
                Map.of("repoId", "orders", "expectedRevision", "FIXTURE", "apiPath", "/orders"),
                ApiRouteMcpDtos.LookupInput.class);
        ApiRouteMcpDtos.SuggestInput suggest = decoder.decode(
                Map.of(
                        "repoId", "orders",
                        "expectedRevision", "FIXTURE",
                        "apiPath", "/orders",
                        "limit", 5),
                ApiRouteMcpDtos.SuggestInput.class);

        invoke(registration(routeTools.registrations(), "semantic_lookup_api_routes"), lookup);
        invoke(registration(routeTools.registrations(), "semantic_suggest_api_routes"), suggest);

        then(apiRouteApplicationService).should().lookupMatches(
                RepositoryId.of("orders"),
                new RepositoryRevision("FIXTURE"),
                "/orders",
                Optional.empty());
        then(apiRouteApplicationService).should().suggestMatches(
                RepositoryId.of("orders"),
                new RepositoryRevision("FIXTURE"),
                "/orders",
                Optional.empty(),
                5);
    }

    private List<String> names(List<McpQueryRegistration<?, ?>> registrations) {
        return registrations.stream().map(McpQueryRegistration::name).toList();
    }

    @SuppressWarnings("unchecked")
    private static <I, O> McpQueryRegistration<I, O> registration(
            List<McpQueryRegistration<?, ?>> registrations,
            String name) {
        return (McpQueryRegistration<I, O>) registrations.stream()
                .filter(registration -> registration.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static <I, O> O invoke(McpQueryRegistration<I, O> registration, I input) {
        return registration.handler().apply(input);
    }
}
