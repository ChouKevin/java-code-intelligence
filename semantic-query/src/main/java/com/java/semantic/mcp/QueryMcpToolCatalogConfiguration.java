package com.java.semantic.mcp;

import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.CodeFactKindUnsupportedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.GitEvidenceNotFoundException;
import com.java.semantic.query.application.GitEvidenceNotReadyException;
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
import com.java.semantic.query.application.ReviewContextMismatchException;
import com.java.semantic.query.application.ReviewFailedException;
import com.java.semantic.query.application.ReviewNotFoundException;
import com.java.semantic.query.application.ReviewNotReadyException;
import com.java.semantic.query.application.ReviewQueryContract;
import com.java.semantic.query.application.ReviewQueryFacade;
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
            SemanticQueryFacade facade, ReviewQueryFacade reviewFacade, ObjectMapper objectMapper) {
        return SemanticMcpToolCatalog.tools().stream()
                .map(tool -> specification(tool, facade, reviewFacade, objectMapper))
                .toList();
    }

    private static McpStatelessServerFeatures.SyncToolSpecification specification(SemanticMcpToolCatalog.ToolDefinition definition,
                                                                                    SemanticQueryFacade facade, ReviewQueryFacade reviewFacade,
                                                                                    ObjectMapper objectMapper) {
        McpSchema.Tool tool = McpSchema.Tool.builder(definition.name(), SemanticMcpSchemaCatalog.inputSchema(definition.name()))
                .description(definition.description())
                .outputSchema(SemanticMcpSchemaCatalog.outputSchema(definition.name()))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .build())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> invokeFacade(definition.name(), request.arguments(), facade, reviewFacade, objectMapper))
                .build();
    }

    private static McpSchema.CallToolResult invokeFacade(String toolName, Map<String, Object> arguments, SemanticQueryFacade facade,
                                                         ReviewQueryFacade reviewFacade, ObjectMapper objectMapper) {
        try {
            Object response = dispatch(toolName, normalizedArguments(toolName, arguments), facade, reviewFacade, objectMapper);
            return McpSchema.CallToolResult.builder().addTextContent("Query completed")
                    .structuredContent(response).isError(false).build();
        } catch (RevisionOutdatedException | RepositoryNotFoundException | ReviewNotFoundException | ReviewNotReadyException
                | ReviewFailedException | ReviewContextMismatchException | GitEvidenceNotFoundException | GitEvidenceNotReadyException
                | CodeFactNotFoundException | CodeFactKindMismatchException | IndexNotReadyException | IndexContractMismatchException
                | SemanticIndexUnavailableException | InvalidCodeFactQueryException | CodeFactKindUnsupportedException
                | IllegalArgumentException exception) {
            return applicationFailure(ERROR_MAPPER.map(exception));
        }
    }

    private static Object dispatch(String toolName, Map<String, Object> arguments, SemanticQueryFacade facade,
                                   ReviewQueryFacade reviewFacade, ObjectMapper objectMapper) {
        return switch (toolName) {
            case "list_git_branches" -> facade.listGitBranches(convert(arguments, SemanticQueryContract.GitBranchRequest.class, objectMapper));
            case "list_git_commits" -> facade.listGitCommits(convert(arguments, SemanticQueryContract.GitCommitRequest.class, objectMapper));
            case "compare_revisions" -> facade.compareRevisions(convert(arguments, SemanticQueryContract.GitComparisonRequest.class, objectMapper));
            case "get_file_diff" -> facade.getFileDiff(convert(arguments, SemanticQueryContract.GitFileDiffRequest.class, objectMapper));
            case "list_files" -> facade.listFiles(convert(arguments, SemanticQueryContract.GitFileListRequest.class, objectMapper));
            case "read_file" -> facade.readFile(convert(arguments, SemanticQueryContract.GitFileReadRequest.class, objectMapper));
            case "search_text" -> facade.searchText(convert(arguments, SemanticQueryContract.GitTextSearchRequest.class, objectMapper));
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
            case "get_review" -> reviewFacade.getReview(convert(arguments, ReviewQueryContract.ReviewRequest.class, objectMapper));
            case "review_search_code" -> reviewFacade.searchCode(convert(arguments, ReviewQueryContract.ReviewSearchCodeRequest.class, objectMapper));
            case "review_get_fact_source" -> reviewFacade.getFactSource(convert(arguments, ReviewQueryContract.ReviewFactSourceRequest.class, objectMapper));
            case "review_list_entry_points" -> reviewFacade.listEntryPoints(convert(arguments, ReviewQueryContract.ReviewEntryPointRequest.class, objectMapper));
            case "review_find_api_routes" -> reviewFacade.findApiRoutes(convert(arguments, ReviewQueryContract.ReviewApiRouteRequest.class, objectMapper));
            case "review_find_event_listeners" -> reviewFacade.findEventListeners(convert(arguments, ReviewQueryContract.ReviewEventListenerRequest.class, objectMapper));
            case "review_list_type_members" -> reviewFacade.listTypeMembers(convert(arguments, ReviewQueryContract.ReviewTypeMemberRequest.class, objectMapper));
            case "review_find_method_implementations", "review_find_references", "review_find_callers", "review_find_callees" ->
                    reviewRelation(toolName, arguments, reviewFacade, objectMapper);
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

    private static Object reviewRelation(String toolName, Map<String, Object> arguments, ReviewQueryFacade reviewFacade,
                                         ObjectMapper objectMapper) {
        ReviewQueryContract.ReviewRelationRequest request = convert(arguments, ReviewQueryContract.ReviewRelationRequest.class, objectMapper);
        return switch (toolName) {
            case "review_find_method_implementations" -> reviewFacade.findMethodImplementations(request);
            case "review_find_references" -> reviewFacade.findReferences(request);
            case "review_find_callers" -> reviewFacade.findCallers(request);
            case "review_find_callees" -> reviewFacade.findCallees(request);
            default -> throw new IllegalArgumentException("unknown Semantic MCP review relation tool");
        };
    }

    private static Map<String, Object> normalizedArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(Objects.requireNonNull(arguments, "MCP arguments are required"));
        if (!SemanticMcpSchemaCatalog.allowedFields(toolName).containsAll(normalized.keySet())) {
            throw new IllegalArgumentException("request contains an unknown field");
        }
        for (String requiredField : SemanticMcpSchemaCatalog.requiredFields(toolName)) {
            if ((toolName.equals("list_files") && requiredField.equals("directory")) || (toolName.equals("search_text") && requiredField.equals("query"))) {
                requiredString(normalized, requiredField);
            } else {
                requiredText(normalized, requiredField);
            }
        }
        if (SemanticMcpSchemaCatalog.allowedFields(toolName).contains("offset")) {
            normalized.putIfAbsent("offset", 0);
            normalized.putIfAbsent("limit", SemanticQueryContract.DEFAULT_LIMIT);
        }
        if (SemanticMcpSchemaCatalog.allowedFields(toolName).contains("contextLines")) {
            normalized.putIfAbsent("contextLines", 0);
        }
        if (toolName.equals("search_code") || toolName.equals("review_search_code")) {
            normalized.putIfAbsent("kinds", Set.of());
            normalized.putIfAbsent("packagePrefix", Optional.empty());
        }
        if (toolName.equals("list_git_branches")) {
            normalized.putIfAbsent("catalogId", Optional.empty());
        }
        if (toolName.equals("read_file")) {
            normalized.putIfAbsent("startLine", Optional.empty());
            normalized.putIfAbsent("maxLines", SemanticQueryContract.DEFAULT_FILE_LINES);
            normalized.putIfAbsent("cursor", Optional.empty());
        }
        if (toolName.equals("search_text")) {
            normalized.putIfAbsent("directory", Optional.empty());
            normalized.putIfAbsent("cursor", Optional.empty());
            normalized.putIfAbsent("limit", SemanticQueryContract.DEFAULT_LIMIT);
        }
        if (toolName.equals("list_entry_points") || toolName.equals("list_type_members")
                || toolName.equals("review_list_entry_points") || toolName.equals("review_list_type_members")) {
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

    private static String requiredString(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (!(value instanceof String text)) {
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
