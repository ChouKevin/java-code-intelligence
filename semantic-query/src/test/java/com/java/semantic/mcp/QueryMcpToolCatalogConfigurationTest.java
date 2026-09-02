package com.java.semantic.mcp;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryError;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryMcpToolCatalogConfigurationTest {

    @Test
    void publishes_exactly_the_twelve_approved_raw_tools_and_closed_schemas() {
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = configuration.mcpQueryToolSpecifications(
                mock(SemanticQueryFacade.class), new ObjectMapper());

        assertEquals(Set.of(
                "list_repositories", "get_repository", "search_code", "get_fact_source",
                "list_entry_points", "find_api_routes", "find_event_listeners", "list_type_members",
                "find_method_implementations", "find_references", "find_callers", "find_callees"),
                specifications.stream().map(specification -> specification.tool().name()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(12, specifications.size());
        for (McpStatelessServerFeatures.SyncToolSpecification specification : specifications) {
            assertFalse((Boolean) specification.tool().inputSchema().get("additionalProperties"));
            assertFalse(specification.tool().description().toLowerCase().contains("first tool"));
        }
    }

    @Test
    void schemas_publish_contract_defaults_bounds_enums_and_identity_fields() {
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = configuration.mcpQueryToolSpecifications(
                mock(SemanticQueryFacade.class), new ObjectMapper());

        for (String name : Set.of("list_repositories", "search_code", "list_entry_points", "find_api_routes",
                "find_event_listeners", "list_type_members", "find_method_implementations", "find_references",
                "find_callers", "find_callees")) {
            Map<String, Object> properties = properties(specification(specifications, name).tool().inputSchema());
            assertEquals(0, property(properties, "offset").get("default"));
            assertEquals(SemanticQueryContract.DEFAULT_LIMIT, property(properties, "limit").get("default"));
            assertEquals(SemanticQueryContract.MAX_LIMIT, property(properties, "limit").get("maximum"));
        }
        Map<String, Object> sourceProperties = properties(specification(specifications, "get_fact_source").tool().inputSchema());
        assertEquals(0, property(sourceProperties, "contextLines").get("default"));
        assertEquals(SemanticQueryContract.MAX_CONTEXT_LINES, property(sourceProperties, "contextLines").get("maximum"));
        assertFalse(properties(specification(specifications, "list_repositories").tool().inputSchema()).containsKey("repositoryId"));
        assertFalse(properties(specification(specifications, "list_repositories").tool().inputSchema()).containsKey("revision"));
        assertEquals(Set.of("repositoryId"), Set.copyOf(required(specification(specifications, "get_repository").tool().inputSchema())));
        assertEquals(Set.of("type", "properties", "required", "additionalProperties"),
                specification(specifications, "get_repository").tool().inputSchema().keySet());
        assertEquals(Set.of(CodeFactKind.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                enumValues(properties(specification(specifications, "search_code")
                .tool().inputSchema()), "kinds"));
        assertEquals(Set.of("METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT"),
                enumValues(properties(specification(specifications, "list_type_members").tool().inputSchema()), "kinds"));
        assertEquals(Set.of(EntryPointKind.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                enumValues(properties(specification(specifications, "list_entry_points")
                .tool().inputSchema()), "kinds"));
        assertEquals(Set.of(SemanticQueryContract.HttpMethod.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                enumValues(properties(specification(specifications, "find_api_routes").tool().inputSchema()), "httpMethod"));
    }

    @Test
    void application_failures_are_returned_as_the_shared_error_with_mcp_error_flag() {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        SemanticQueryError expected = new SemanticQueryError("FACT_NOT_FOUND", "The requested fact was not found.", false,
                java.util.Optional.empty());
        when(facade.getFactSource(any())).thenThrow(new com.java.semantic.query.application.CodeFactNotFoundException());
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpStatelessServerFeatures.SyncToolSpecification specification = specification(configuration.mcpQueryToolSpecifications(facade,
                new ObjectMapper()), "get_fact_source");

        McpSchema.CallToolResult result = specification.callHandler().apply(null, new McpSchema.CallToolRequest("get_fact_source", Map.of(
                "repositoryId", "orders", "revision", "a".repeat(40), "factId", "b".repeat(64))));

        assertTrue(result.isError());
        assertEquals(expected, result.structuredContent());
    }

    @Test
    void dispatches_each_approved_tool_to_its_named_facade_operation() {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = configuration.mcpQueryToolSpecifications(facade,
                new ObjectMapper());

        invoke(specifications, "list_repositories", Map.of());
        invoke(specifications, "get_repository", Map.of("repositoryId", "orders"));
        invoke(specifications, "search_code", repositoryRequest(Map.of("query", "payment")));
        invoke(specifications, "get_fact_source", repositoryRequest(Map.of("factId", "b".repeat(64))));
        invoke(specifications, "list_entry_points", repositoryRequest(Map.of()));
        invoke(specifications, "find_api_routes", repositoryRequest(Map.of("httpMethod", "GET", "path", "/payments")));
        invoke(specifications, "find_event_listeners", repositoryRequest(Map.of("eventType", "com.example.PaymentCreated")));
        invoke(specifications, "list_type_members", repositoryRequest(Map.of("typeFactId", "b".repeat(64))));
        invoke(specifications, "find_method_implementations", repositoryRequest(Map.of("methodFactId", "b".repeat(64))));
        invoke(specifications, "find_references", repositoryRequest(Map.of("factId", "b".repeat(64))));
        invoke(specifications, "find_callers", repositoryRequest(Map.of("methodFactId", "b".repeat(64))));
        invoke(specifications, "find_callees", repositoryRequest(Map.of("methodFactId", "b".repeat(64))));

        verify(facade).listRepositories(any());
        verify(facade).getRepository(any());
        verify(facade).searchCode(any());
        verify(facade).getFactSource(any());
        verify(facade).listEntryPoints(any());
        verify(facade).findApiRoutes(any());
        verify(facade).findEventListeners(any());
        verify(facade).listTypeMembers(any());
        verify(facade).findMethodImplementations(any());
        verify(facade).findReferences(any());
        verify(facade).findCallers(any());
        verify(facade).findCallees(any());

        ArgumentCaptor<SemanticQueryContract.SearchCodeRequest> searchRequest = ArgumentCaptor.forClass(
                SemanticQueryContract.SearchCodeRequest.class);
        verify(facade).searchCode(searchRequest.capture());
        assertEquals(0, searchRequest.getValue().offset());
        assertEquals(SemanticQueryContract.DEFAULT_LIMIT, searchRequest.getValue().limit());
        assertEquals(Set.of(), searchRequest.getValue().kinds());
        assertEquals(Optional.empty(), searchRequest.getValue().packagePrefix());

        ArgumentCaptor<SemanticQueryContract.EntryPointRequest> entryPointRequest = ArgumentCaptor.forClass(
                SemanticQueryContract.EntryPointRequest.class);
        verify(facade).listEntryPoints(entryPointRequest.capture());
        assertEquals(Set.of(), entryPointRequest.getValue().kinds());

        ArgumentCaptor<SemanticQueryContract.RelationRequest> callerRequest = ArgumentCaptor.forClass(
                SemanticQueryContract.RelationRequest.class);
        verify(facade).findCallers(callerRequest.capture());
        assertEquals("b".repeat(64), callerRequest.getValue().factId());
    }

    private static void invoke(List<McpStatelessServerFeatures.SyncToolSpecification> specifications, String name,
                               Map<String, Object> arguments) {
        specification(specifications, name).callHandler().apply(null, new McpSchema.CallToolRequest(name, arguments));
    }

    private static Map<String, Object> repositoryRequest(Map<String, Object> arguments) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(arguments);
        request.put("repositoryId", "orders");
        request.put("revision", "a".repeat(40));
        return Map.copyOf(request);
    }

    private static McpStatelessServerFeatures.SyncToolSpecification specification(
            List<McpStatelessServerFeatures.SyncToolSpecification> specifications, String name) {
        return specifications.stream().filter(candidate -> name.equals(candidate.tool().name())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> properties, String name) {
        return (Map<String, Object>) properties.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schema) {
        return (List<String>) schema.get("required");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumValues(Map<String, Object> properties, String name) {
        Map<String, Object> field = property(properties, name);
        Object items = field.get("items");
        if (items instanceof Map<?, ?> itemSchema) {
            return Set.copyOf((List<String>) itemSchema.get("enum"));
        }
        return Set.copyOf((List<String>) field.get("enum"));
    }
}
