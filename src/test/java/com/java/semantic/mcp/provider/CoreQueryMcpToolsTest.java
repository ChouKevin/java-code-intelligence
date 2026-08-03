package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.dto.repository.RepositoryMcpDtos;
import com.java.semantic.mcp.dto.route.ApiRouteMcpDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 core query provider 的 MCP 邊界契約 */
@SpringBootTest
class CoreQueryMcpToolsTest {

    @Autowired
    private RepositoryMcpTools repositoryTools;

    @Autowired
    private CallGraphMcpTools callGraphTools;

    @Autowired
    private ApiRouteMcpTools routeTools;

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

    private List<String> names(List<McpQueryRegistration<?, ?>> registrations) {
        return registrations.stream().map(McpQueryRegistration::name).toList();
    }
}
