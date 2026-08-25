package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void schemas_allow_typed_failures_alongside_generation_and_metadata_successes() {
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification> specifications =
                configuration.mcpQueryToolSpecifications(mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class),
                        mock(com.java.semantic.query.application.CodeFactSearchService.class), mock(com.java.semantic.query.application.CodeFactReadService.class),
                        mock(com.java.semantic.query.application.PublishedCallGraphService.class), mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class),
                        mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class), mock(com.java.semantic.query.application.PublishedRelationQueryService.class),
                        mock(com.java.semantic.query.application.PublishedSourceToolService.class));

        Map<String, Object> generationSchema = specification(specifications, "semantic_search_code_facts").tool().outputSchema();
        Map<String, Object> metadataSchema = specification(specifications, "semantic_list_repositories").tool().outputSchema();
        assertEquals(3, ((List<?>) generationSchema.get("oneOf")).size());
        assertEquals(2, ((List<?>) metadataSchema.get("oneOf")).size());
        assertThatSchemaAllowsFailure(generationSchema);
        assertThatSchemaAllowsFailure(metadataSchema);
    }

    @Test
    void schemas_use_exact_identifiers_query_bounds_and_type_member_kinds() {
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification> specifications =
                configuration.mcpQueryToolSpecifications(mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class),
                        mock(com.java.semantic.query.application.CodeFactSearchService.class), mock(com.java.semantic.query.application.CodeFactReadService.class),
                        mock(com.java.semantic.query.application.PublishedCallGraphService.class), mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class),
                        mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class), mock(com.java.semantic.query.application.PublishedRelationQueryService.class),
                        mock(com.java.semantic.query.application.PublishedSourceToolService.class));

        Map<String, Map<String, Object>> searchProperties = properties(specification(specifications, "semantic_search_code_facts").tool().inputSchema());
        assertEquals("^[a-z0-9][a-z0-9._-]{0,63}$", property(searchProperties, "repositoryId").get("pattern"));
        assertEquals("^[0-9a-f]{40}$", property(searchProperties, "revision").get("pattern"));
        assertEquals(2, property(searchProperties, "query").get("minLength"));
        assertEquals(256, property(searchProperties, "query").get("maxLength"));
        Map<String, Map<String, Object>> getProperties = properties(specification(specifications, "semantic_get_code_fact").tool().inputSchema());
        assertEquals("^[0-9a-f]{64}$", property(getProperties, "factId").get("pattern"));
        Map<String, Map<String, Object>> membersProperties = properties(specification(specifications, "semantic_discover_type_members").tool().inputSchema());
        Map<String, Object> kindItems = map(property(membersProperties, "kinds").get("items"));
        assertEquals(Set.of("METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT"),
                Set.copyOf((List<String>) kindItems.get("enum")));
    }

    @Test
    void execution_enforces_search_input_boundaries() {
        com.java.semantic.query.application.CodeFactSearchService searchService = mock(com.java.semantic.query.application.CodeFactSearchService.class);
        when(searchService.search(any())).thenReturn(null);
        java.util.Map<String, Object> valid = java.util.Map.of("repositoryId", "main", "revision", "a".repeat(40), "query", "ok");
        QueryMcpToolCatalogConfiguration.execute(requirement("semantic_search_code_facts"), valid,
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class), searchService,
                mock(com.java.semantic.query.application.CodeFactReadService.class), mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class), mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class), mock(com.java.semantic.query.application.PublishedSourceToolService.class));
        verify(searchService).search(any());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> QueryMcpToolCatalogConfiguration.execute(
                requirement("semantic_search_code_facts"), java.util.Map.of("repositoryId", "main", "revision", "a".repeat(40), "query", "x"),
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class), searchService,
                mock(com.java.semantic.query.application.CodeFactReadService.class), mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class), mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class), mock(com.java.semantic.query.application.PublishedSourceToolService.class)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> QueryMcpToolCatalogConfiguration.execute(
                requirement("semantic_search_code_facts"), java.util.Map.of("repositoryId", "MAIN", "revision", "a".repeat(40), "query", "ok"),
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class), searchService,
                mock(com.java.semantic.query.application.CodeFactReadService.class), mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class), mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class), mock(com.java.semantic.query.application.PublishedSourceToolService.class)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> QueryMcpToolCatalogConfiguration.execute(
                requirement("semantic_get_code_fact"), java.util.Map.of("repositoryId", "main", "revision", "a".repeat(40), "factId", "invalid"),
                mock(com.java.semantic.query.application.CurrentRepositoryQueryService.class), searchService,
                mock(com.java.semantic.query.application.CodeFactReadService.class), mock(com.java.semantic.query.application.PublishedCallGraphService.class),
                mock(com.java.semantic.query.application.PublishedDiscoveryQueryService.class), mock(com.java.semantic.query.application.PublishedEntryPointQueryService.class),
                mock(com.java.semantic.query.application.PublishedRelationQueryService.class), mock(com.java.semantic.query.application.PublishedSourceToolService.class)));
    }

    private static io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification specification(
            List<io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification> specifications, String toolName) {
        return specifications.stream().filter(candidate -> toolName.equals(candidate.tool().name())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> properties(Map<String, Object> schema) {
        return (Map<String, Map<String, Object>>) schema.get("properties");
    }

    private static Map<String, Object> property(Map<String, Map<String, Object>> properties, String name) {
        return properties.get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static void assertThatSchemaAllowsFailure(Map<String, Object> schema) {
        List<Map<String, Object>> alternatives = (List<Map<String, Object>>) schema.get("oneOf");
        Map<String, Object> failure = alternatives.get(alternatives.size() - 1);
        assertEquals(java.util.List.of("code", "retryable"), failure.get("required"));
    }

    private static com.java.semantic.model.query.ToolProjectionRequirement requirement(String toolName) {
        return ToolProjectionCatalog.requirements().stream().filter(candidate -> toolName.equals(candidate.toolName()))
                .findFirst().orElseThrow();
    }
}
