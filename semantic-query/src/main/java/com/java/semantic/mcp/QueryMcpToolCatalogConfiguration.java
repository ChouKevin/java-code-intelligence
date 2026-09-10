package com.java.semantic.mcp;

import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.CodeFactKindUnsupportedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.IndexContractMismatchException;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.InvalidCodeFactQueryException;
import com.java.semantic.query.application.RepositoryNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticIndexUnavailableException;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryError;
import com.java.semantic.query.application.SemanticQueryErrorMapper;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Publishes the finite Query-side MCP catalog without discovering tools from the Indexer. */
@Configuration
public class QueryMcpToolCatalogConfiguration {

    private static final SemanticQueryErrorMapper ERROR_MAPPER = new SemanticQueryErrorMapper();

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> mcpQueryToolSpecifications(
            SemanticQueryFacade facade, ObjectMapper objectMapper) {
        return SemanticMcpToolCatalog.tools().stream()
                .map(tool -> specification(tool, facade, objectMapper))
                .toList();
    }

    private static McpStatelessServerFeatures.SyncToolSpecification specification(SemanticMcpToolCatalog.ToolDefinition definition,
                                                                                    SemanticQueryFacade facade,
                                                                                    ObjectMapper objectMapper) {
        McpSchema.Tool tool = McpSchema.Tool.builder(definition.name())
                .description(definition.description())
                .inputSchema(SemanticMcpSchemaCatalog.inputSchema(definition.name()))
                .outputSchema(SemanticMcpSchemaCatalog.outputSchema(definition.name()))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .build())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> invokeFacade(definition.name(), request.arguments(), facade, objectMapper))
                .build();
    }

    private static McpSchema.CallToolResult invokeFacade(String toolName, Map<String, Object> arguments, SemanticQueryFacade facade,
                                                         ObjectMapper objectMapper) {
        try {
            Object response = dispatch(toolName, normalizedArguments(toolName, arguments), facade, objectMapper);
            return McpSchema.CallToolResult.builder().addTextContent("Query completed")
                    .structuredContent(response).isError(false).build();
        } catch (RevisionOutdatedException | RepositoryNotFoundException | CodeFactNotFoundException
                | CodeFactKindMismatchException | IndexNotReadyException | IndexContractMismatchException
                | SemanticIndexUnavailableException | InvalidCodeFactQueryException | CodeFactKindUnsupportedException
                | IllegalArgumentException exception) {
            return applicationFailure(ERROR_MAPPER.map(exception));
        }
    }

    private static Object dispatch(String toolName, Map<String, Object> arguments, SemanticQueryFacade facade, ObjectMapper objectMapper) {
        return switch (toolName) {
            case "list_repositories" -> facade.listRepositories(convert(arguments, SemanticQueryContract.PageRequest.class, objectMapper));
            case "get_repository" -> facade.getRepository(convert(arguments, SemanticQueryContract.RepositoryRequest.class, objectMapper));
            case "search_code" -> facade.searchCode(convert(arguments, SemanticQueryContract.SearchCodeRequest.class, objectMapper));
            case "get_fact_source" -> facade.getFactSource(convert(arguments, SemanticQueryContract.FactSourceRequest.class, objectMapper));
            case "list_entry_points" -> facade.listEntryPoints(convert(arguments, SemanticQueryContract.EntryPointRequest.class, objectMapper));
            case "find_api_routes" -> facade.findApiRoutes(convert(arguments, SemanticQueryContract.ApiRouteRequest.class, objectMapper));
            case "find_event_listeners" -> facade.findEventListeners(convert(arguments, SemanticQueryContract.EventListenerRequest.class, objectMapper));
            case "list_type_members" -> facade.listTypeMembers(convert(arguments, SemanticQueryContract.TypeMemberRequest.class, objectMapper));
            case "find_method_implementations", "find_callers", "find_callees", "find_references" ->
                    relation(toolName, arguments, facade, objectMapper);
            default -> throw new IllegalArgumentException("unknown Semantic MCP tool");
        };
    }

    private static Object relation(String toolName, Map<String, Object> arguments, SemanticQueryFacade facade, ObjectMapper objectMapper) {
        SemanticQueryContract.RelationRequest request = convert(arguments, SemanticQueryContract.RelationRequest.class, objectMapper);
        return switch (toolName) {
            case "find_method_implementations" -> facade.findMethodImplementations(request);
            case "find_references" -> facade.findReferences(request);
            case "find_callers" -> facade.findCallers(request);
            case "find_callees" -> facade.findCallees(request);
            default -> throw new IllegalArgumentException("unknown Semantic MCP relation tool");
        };
    }

    private static Map<String, Object> normalizedArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(Objects.requireNonNull(arguments, "MCP arguments are required"));
        if (!SemanticMcpSchemaCatalog.allowedFields(toolName).containsAll(normalized.keySet())) {
            throw new IllegalArgumentException("request contains an unknown field");
        }
        for (String requiredField : SemanticMcpSchemaCatalog.requiredFields(toolName)) {
            requiredText(normalized, requiredField);
        }
        if (SemanticMcpSchemaCatalog.allowedFields(toolName).contains("offset")) {
            normalized.putIfAbsent("offset", 0);
            normalized.putIfAbsent("limit", SemanticQueryContract.DEFAULT_LIMIT);
        }
        if (SemanticMcpSchemaCatalog.allowedFields(toolName).contains("contextLines")) {
            normalized.putIfAbsent("contextLines", 0);
        }
        if (toolName.equals("search_code")) {
            normalized.putIfAbsent("kinds", Set.of());
            normalized.putIfAbsent("packagePrefix", Optional.empty());
        }
        if (toolName.equals("list_entry_points") || toolName.equals("list_type_members")) {
            normalized.putIfAbsent("kinds", Set.of());
        }
        if (normalized.containsKey("methodFactId")) {
            normalized.put("factId", requiredText(normalized, "methodFactId"));
            normalized.remove("methodFactId");
        }
        return Map.copyOf(normalized);
    }

    private static String requiredText(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private static <T> T convert(Map<String, Object> arguments, Class<T> targetType, ObjectMapper objectMapper) {
        try {
            return objectMapper.convertValue(arguments, targetType);
        } catch (DatabindException exception) {
            throw new IllegalArgumentException("request does not satisfy the operation contract", exception);
        }
    }

    private static McpSchema.CallToolResult applicationFailure(SemanticQueryError error) {
        return McpSchema.CallToolResult.builder().addTextContent(error.message()).structuredContent(error).isError(true).build();
    }

}
