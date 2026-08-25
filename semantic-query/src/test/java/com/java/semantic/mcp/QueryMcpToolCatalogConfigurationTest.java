package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        assertEquals(expected, result);
        verify(entryPoints).listEntryPoints("orders", "a".repeat(40));
    }

    private static com.java.semantic.model.query.ToolProjectionRequirement requirement(String toolName) {
        return ToolProjectionCatalog.requirements().stream().filter(candidate -> toolName.equals(candidate.toolName()))
                .findFirst().orElseThrow();
    }
}
