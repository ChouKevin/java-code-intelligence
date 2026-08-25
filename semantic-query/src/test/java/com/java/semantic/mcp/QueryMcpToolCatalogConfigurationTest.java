package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class QueryMcpToolCatalogConfigurationTest {

    @Test
    void acceptance_catalog_and_authoritative_catalog_publish_the_same_exact_tools() throws Exception {
        String acceptance = Files.readString(Path.of("..", "acceptance", "tool-cases.json"));
        List<String> tools = ToolProjectionCatalog.toolNames();

        assertEquals(17, tools.size());
        assertEquals(tools, tools.stream().sorted().toList());
        for (String tool : tools) {
            org.junit.jupiter.api.Assertions.assertTrue(acceptance.contains("\"" + tool + "\""));
        }
    }

    @Test
    void registered_mcp_specifications_are_exactly_the_authoritative_read_only_catalog() {
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification> specifications =
                configuration.mcpQueryToolSpecifications(
                        mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class),
                        mock(com.java.semantic.query.application.CodeFactSearchService.class),
                        mock(com.java.semantic.query.application.CodeFactReadService.class),
                        mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                        mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class),
                        mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                        mock(com.java.semantic.query.application.PublishedRelationQueryService.class),
                        mock(com.java.semantic.query.application.PublishedSourceToolService.class));

        assertEquals(ToolProjectionCatalog.toolNames(), specifications.stream()
                .map(specification -> specification.tool().name()).toList());
    }

    @Test
    void entry_point_tool_uses_the_persisted_query_reader_with_the_exact_revision() {
        com.java.semantic.query.application.CurrentRepositoryQueryService repositories =
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class);
        com.java.semantic.query.application.PublishedEntryPointQueryService entryPoints =
                mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class);
        List<com.java.semantic.model.codefact.PublishedEntryPoint> expected = List.of();
        when(entryPoints.listEntryPoints("orders", "a".repeat(40))).thenReturn(expected);

        Object result = QueryMcpToolCatalogConfiguration.execute(requirement("semantic_list_entry_points"),
                java.util.Map.of("repositoryId", "orders", "revision", "a".repeat(40)), repositories,
                mock(com.java.semantic.query.application.CodeFactSearchService.class),
                mock(com.java.semantic.query.application.CodeFactReadService.class),
                mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class), entryPoints,
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class),
                mock(com.java.semantic.query.application.PublishedSourceToolService.class));

        assertEquals(new QueryMcpToolCatalogConfiguration.GenerationBackedResponse("orders", "a".repeat(40), expected), result);
        verify(entryPoints).listEntryPoints("orders", "a".repeat(40));
    }

    @Test
    void registered_code_fact_search_preserves_optional_filters_and_page_bounds() {
        com.java.semantic.query.application.CodeFactSearchService searchService =
                mock(com.java.semantic.query.application.CodeFactSearchService.class);
        when(searchService.search(any())).thenReturn(null);

        QueryMcpToolCatalogConfiguration.execute(requirement("semantic_search_code_facts"), java.util.Map.of(
                "repositoryId", "orders", "revision", "a".repeat(40), "query", "payment",
                "kinds", List.of("METHOD", "TYPE"), "packagePrefix", "com.example.orders",
                "offset", 4, "limit", 12),
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class), searchService,
                mock(com.java.semantic.query.application.CodeFactReadService.class),
                mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class),
                mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class),
                mock(com.java.semantic.query.application.PublishedSourceToolService.class));

        org.mockito.ArgumentCaptor<com.java.semantic.model.codefact.CodeFactSearchQuery> query =
                org.mockito.ArgumentCaptor.forClass(com.java.semantic.model.codefact.CodeFactSearchQuery.class);
        verify(searchService).search(query.capture());
        assertEquals(java.util.Set.of(com.java.semantic.model.codefact.CodeFactKind.METHOD,
                com.java.semantic.model.codefact.CodeFactKind.TYPE), query.getValue().kinds());
        assertEquals(java.util.Optional.of("com.example.orders"), query.getValue().packagePrefix());
        assertEquals(4, query.getValue().offset());
        assertEquals(12, query.getValue().limit());
    }

    @Test
    void mcp_failures_expose_the_stable_typed_body_as_structured_content() {
        java.util.Map<String, Object> expected = java.util.Map.of("code", "SEMANTIC_INDEX_UNAVAILABLE", "retryable", true);

        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = QueryMcpToolCatalogConfiguration.failure(expected);

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(expected, result.structuredContent());
    }

    private static com.java.semantic.model.query.ToolProjectionRequirement requirement(String toolName) {
        return ToolProjectionCatalog.requirements().stream().filter(candidate -> toolName.equals(candidate.toolName()))
                .findFirst().orElseThrow();
    }
}
