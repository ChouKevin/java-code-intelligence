package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("mongo-it")
class ToolProjectionCatalogTest {

    @Test
    void publishes_the_exact_read_only_tool_catalog_with_projection_requirements() {
        assertEquals(List.of(
                        "semantic_analyze_incoming_call_graph",
                        "semantic_analyze_outgoing_call_graph",
                        "semantic_discover_event_listeners",
                        "semantic_discover_method_implementations",
                        "semantic_discover_type_members",
                        "semantic_find_internal_references",
                        "semantic_get_code_fact",
                        "semantic_get_evidence_source",
                        "semantic_get_method_source",
                        "semantic_get_repository",
                        "semantic_get_source_segment",
                        "semantic_list_entry_points",
                        "semantic_list_repositories",
                        "semantic_lookup_api_routes",
                        "semantic_resolve_source_symbol",
                        "semantic_search_code_facts",
                        "semantic_suggest_api_routes"),
                ToolProjectionCatalog.toolNames());
        assertEquals(17, ToolProjectionCatalog.requirements().size());
    }

    @Test
    void code_fact_catalog_declares_the_search_stage_and_leaves_authority_selection_to_the_stored_kind() {
        assertThat(requirement("semantic_search_code_facts").projections().orElseThrow().names())
                .containsExactly(com.java.semantic.model.index.ProjectionName.SEARCH);
        assertThat(requirement("semantic_get_code_fact").projections().orElseThrow().names())
                .containsExactly(com.java.semantic.model.index.ProjectionName.SEARCH);
    }

    @Test
    void rejects_catalog_drift_including_a_new_runtime_tool_without_an_acceptance_case() {
        List<com.java.semantic.model.query.ToolProjectionRequirement> requirements = ToolProjectionCatalog.requirements();
        Set<com.java.semantic.model.index.ProjectionName> produced = com.java.semantic.model.index.IndexSchemaContract.projectionNames();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ToolProjectionCatalog.validate(requirements, produced, produced,
                        List.of("semantic_search_code_facts", "semantic_new_tool"), List.of("semantic_search_code_facts")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("every runtime tool must have a projection requirement");
        List<com.java.semantic.model.query.ToolProjectionRequirement> expandedRequirements = new java.util.ArrayList<>(requirements);
        expandedRequirements.add(com.java.semantic.model.query.ToolProjectionRequirement.metadata("semantic_new_tool"));
        List<String> expandedRuntime = new java.util.ArrayList<>(ToolProjectionCatalog.toolNames());
        expandedRuntime.add("semantic_new_tool");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ToolProjectionCatalog.validate(expandedRequirements, produced, produced,
                        expandedRuntime, ToolProjectionCatalog.toolNames()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("every runtime MCP tool must have exactly one acceptance case");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ToolProjectionCatalog.validate(requirements, produced,
                        Set.of(com.java.semantic.model.index.ProjectionName.SEARCH), ToolProjectionCatalog.toolNames(), ToolProjectionCatalog.toolNames()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("every produced projection requires validator and count coverage");
    }

    private static com.java.semantic.model.query.ToolProjectionRequirement requirement(String toolName) {
        return ToolProjectionCatalog.requirements().stream().filter(candidate -> candidate.toolName().equals(toolName))
                .findFirst().orElseThrow();
    }
}
