package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
