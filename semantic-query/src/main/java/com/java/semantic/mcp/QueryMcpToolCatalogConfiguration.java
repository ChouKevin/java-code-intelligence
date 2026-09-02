package com.java.semantic.mcp;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.DeclarationResolutionQuery;
import com.java.semantic.model.codefact.EventListenerQuery;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.query.PublishedCallGraphQuery;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.ToolProjectionRequirement;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.CodeFactKindUnsupportedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.IndexContractMismatchException;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.InvalidCodeFactQueryException;
import com.java.semantic.query.application.PublishedCallGraphService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.PublishedSourceToolService;
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
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                .outputSchema(Map.of())
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

    private static <T> T convert(Map<String, Object> arguments, Class<T> targetType, ObjectMapper objectMapper) {
        return objectMapper.convertValue(arguments, targetType);
    }

    private static McpSchema.CallToolResult applicationFailure(SemanticQueryError error) {
        return McpSchema.CallToolResult.builder().addTextContent(error.message()).structuredContent(error).isError(true).build();
    }

    private static Set<String> allowedFields(String toolName, boolean generationBacked) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(generationBacked ? List.of("repositoryId", "revision")
                : "semantic_get_repository".equals(toolName) ? List.of("repositoryId") : List.of());
        switch (toolName) {
            case "semantic_analyze_incoming_call_graph", "semantic_analyze_outgoing_call_graph" ->
                    fields.addAll(List.of("packageName", "className", "sourceFile", "methodName", "parameterTypes", "depth", "depthTwoNodeBudget"));
            case "semantic_discover_method_implementations", "semantic_find_internal_references" ->
                    fields.addAll(List.of("packageName", "className", "sourceFile", "methodName", "parameterTypes", "offset", "limit"));
            case "semantic_get_evidence_source", "semantic_get_method_source" ->
                    fields.addAll(List.of("packageName", "className", "sourceFile", "methodName", "parameterTypes"));
            case "semantic_discover_event_listeners" -> fields.addAll(List.of("eventType", "offset", "limit"));
            case "semantic_discover_type_members" -> fields.addAll(List.of("packageName", "className", "sourceFile", "kinds", "offset", "limit"));
            case "semantic_get_source_segment" -> fields.addAll(List.of("packageName", "className", "sourceFile", "startLine", "startCharacter", "endLine", "endCharacter"));
            case "semantic_lookup_api_routes", "semantic_suggest_api_routes" -> fields.addAll(List.of("httpMethod", "path"));
            case "semantic_resolve_source_symbol" -> fields.addAll(List.of("packageName", "className", "sourceFile", "symbol", "line", "character"));
            case "semantic_search_code_facts" -> fields.addAll(List.of("query", "kinds", "packagePrefix", "offset", "limit"));
            case "semantic_get_code_fact" -> fields.add("factId");
            default -> { }
        }
        return Set.copyOf(fields);
    }

    private static List<String> requiredFields(String toolName, boolean generationBacked) {
        List<String> identity = generationBacked ? List.of("repositoryId", "revision")
                : "semantic_get_repository".equals(toolName) ? List.of("repositoryId") : List.of();
        return switch (toolName) {
            case "semantic_analyze_incoming_call_graph", "semantic_analyze_outgoing_call_graph",
                    "semantic_discover_method_implementations", "semantic_find_internal_references",
                    "semantic_get_evidence_source", "semantic_get_method_source" -> append(identity,
                    "packageName", "className", "sourceFile", "methodName", "parameterTypes");
            case "semantic_discover_event_listeners" -> append(identity, "eventType");
            case "semantic_discover_type_members" -> append(identity, "packageName", "className", "sourceFile", "kinds");
            case "semantic_get_source_segment" -> append(identity, "packageName", "className", "sourceFile",
                    "startLine", "startCharacter", "endLine", "endCharacter");
            case "semantic_lookup_api_routes", "semantic_suggest_api_routes" ->
                    append(identity, "httpMethod", "path");
            case "semantic_resolve_source_symbol" -> append(identity, "packageName", "className", "sourceFile", "symbol");
            case "semantic_search_code_facts" -> append(identity, "query");
            case "semantic_get_code_fact" -> append(identity, "factId");
            default -> identity;
        };
    }

    private static List<String> append(List<String> fields, String... additions) {
        List<String> result = new java.util.ArrayList<>(fields);
        result.addAll(List.of(additions));
        return List.copyOf(result);
    }

    /** Shared HTTP/MCP execution path: all results originate in published Query readers. */
    public static Object execute(
            ToolProjectionRequirement requirement,
            Map<String, Object> arguments,
            CurrentRepositoryQueryService repositories,
            CodeFactSearchService codeFactSearchService,
            CodeFactReadService codeFactReadService,
            PublishedCallGraphService callGraphs,
            PublishedDiscoveryQueryService discovery,
            PublishedEntryPointQueryService entryPoints,
            PublishedRelationQueryService relations,
            PublishedSourceToolService sourceTools) {
        Map<String, Object> request = Objects.requireNonNull(arguments, "query arguments are required");
        rejectLegacyIdentityFields(request);
        rejectUnknownFields(request, requirement);
        Object result = switch (requirement.toolName()) {
                case "semantic_list_repositories" -> repositories.listRepositories();
                case "semantic_get_repository" -> repositories.getRepository(requiredText(request, "repositoryId"));
                case "semantic_search_code_facts" -> searchCodeFacts(request, codeFactSearchService);
                case "semantic_get_code_fact" -> getCodeFact(request, codeFactReadService);
                case "semantic_analyze_incoming_call_graph" -> callGraphs.incoming(callGraphQuery(request));
                case "semantic_analyze_outgoing_call_graph" -> callGraphs.outgoing(callGraphQuery(request));
                case "semantic_discover_event_listeners" -> discovery.discoverEventListeners(eventListenerQuery(request));
                case "semantic_discover_method_implementations" -> relations.findImplementations(relationQuery(request));
                case "semantic_discover_type_members" -> discovery.discoverTypeMembers(typeMemberQuery(request));
                case "semantic_find_internal_references" -> relations.findReferences(relationQuery(request));
                case "semantic_get_evidence_source" -> sourceTools.evidenceSource(repositoryId(request), revision(request), methodIdentity(request));
                case "semantic_get_method_source" -> sourceTools.methodSource(repositoryId(request), revision(request), methodIdentity(request));
                case "semantic_get_source_segment" -> sourceTools.sourceSegment(sourceSegmentQuery(request));
                case "semantic_lookup_api_routes", "semantic_suggest_api_routes" -> entryPoints.findRoutes(
                        repositoryId(request), revision(request), requiredText(request, "httpMethod"), requiredText(request, "path"));
                case "semantic_resolve_source_symbol" -> discovery.resolveDeclaration(declarationResolutionQuery(request));
            case "semantic_list_entry_points" -> entryPoints.listEntryPoints(repositoryId(request), revision(request));
            default -> throw new IllegalStateException("unregistered MCP tool");
        };
        return requirement.projections().isPresent()
                ? new GenerationBackedResponse(repositoryId(request), revision(request), result) : result;
    }

    private static CodeFactSearchResult searchCodeFacts(Map<String, Object> request, CodeFactSearchService service) {
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredText(request, "repositoryId")),
                new RepositoryRevision(requiredText(request, "revision")), requiredText(request, "query"),
                codeFactKinds(request), optionalText(request, "packagePrefix"), integer(request, "offset", CodeFactSearchQuery.DEFAULT_OFFSET),
                integer(request, "limit", CodeFactSearchQuery.DEFAULT_LIMIT));
        return service.search(query);
    }

    private static Set<CodeFactKind> codeFactKinds(Map<String, Object> request) {
        Set<CodeFactKind> kinds = new LinkedHashSet<>();
        for (String value : stringList(request, "kinds")) {
            try {
                kinds.add(CodeFactKind.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("kinds contains an unsupported CodeFactKind");
            }
        }
        return Set.copyOf(kinds);
    }

    private static Optional<String> optionalText(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (Objects.isNull(value)) {
            return Optional.empty();
        }
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " must be a nonblank string when supplied");
        }
        return Optional.of(text);
    }

    private static Object getCodeFact(Map<String, Object> request, CodeFactReadService service) {
        CodeFactReadQuery query = new CodeFactReadQuery(new RepositoryId(requiredText(request, "repositoryId")),
                new RepositoryRevision(requiredText(request, "revision")), new CodeFactId(requiredText(request, "factId")));
        return service.get(query);
    }

    private static PublishedCallGraphQuery callGraphQuery(Map<String, Object> request) {
        return new PublishedCallGraphQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)),
                methodTarget(request), integer(request, "depth", 1), integer(request, "depthTwoNodeBudget", 100));
    }

    private static EventListenerQuery eventListenerQuery(Map<String, Object> request) {
        return new EventListenerQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)),
                requiredText(request, "eventType"), integer(request, "offset", 0), integer(request, "limit", 50));
    }

    private static PublishedRelationQuery relationQuery(Map<String, Object> request) {
        return new PublishedRelationQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)),
                methodIdentity(request), integer(request, "offset", 0), integer(request, "limit", 100));
    }

    private static TypeMemberQuery typeMemberQuery(Map<String, Object> request) {
        Set<CodeFactKind> kinds = new LinkedHashSet<>();
        for (String kind : stringList(request, "kinds")) {
            kinds.add(CodeFactKind.valueOf(kind));
        }
        return new TypeMemberQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)), sourceType(request),
                kinds, integer(request, "offset", 0), integer(request, "limit", 100));
    }

    private static DeclarationResolutionQuery declarationResolutionQuery(Map<String, Object> request) {
        Optional<SyntaxPosition> position = Optional.empty();
        if (request.containsKey("line") || request.containsKey("character")) {
            position = Optional.of(new SyntaxPosition(integer(request, "line", -1), integer(request, "character", -1)));
        }
        return new DeclarationResolutionQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)),
                sourceType(request), requiredText(request, "symbol"), position);
    }

    private static SourceSegmentQuery sourceSegmentQuery(Map<String, Object> request) {
        SourceTypeIdentity sourceType = sourceType(request);
        SourceRange range = new SourceRange(sourceType.sourceFile(), new SyntaxRange(
                new SyntaxPosition(integer(request, "startLine", -1), integer(request, "startCharacter", -1)),
                new SyntaxPosition(integer(request, "endLine", -1), integer(request, "endCharacter", -1))));
        return new SourceSegmentQuery(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)), sourceType, range);
    }

    private static CodeFactIdentity methodIdentity(Map<String, Object> request) {
        return new CodeFactIdentity(new RepositoryId(repositoryId(request)), new RepositoryRevision(revision(request)),
                CodeFactKind.METHOD, methodTarget(request));
    }

    private static MethodTarget methodTarget(Map<String, Object> request) {
        return new MethodTarget(sourceType(request), requiredText(request, "methodName"), stringList(request, "parameterTypes"));
    }

    private static SourceTypeIdentity sourceType(Map<String, Object> request) {
        return new SourceTypeIdentity(new JavaTypeIdentity(requiredText(request, "packageName"), requiredText(request, "className")),
                requiredText(request, "sourceFile"));
    }

    private static String repositoryId(Map<String, Object> request) {
        return requiredText(request, "repositoryId");
    }

    private static String revision(Map<String, Object> request) {
        return requiredText(request, "revision");
    }

    private static int integer(Map<String, Object> request, String field, int defaultValue) {
        Object value = request.get(field);
        if (Objects.isNull(value)) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static List<String> stringList(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (Objects.isNull(value)) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return values.stream().map(element -> {
            if (!(element instanceof String text) || !StringUtils.hasText(text)) {
                throw new IllegalArgumentException(field + " values must be nonblank strings");
            }
            return text;
        }).toList();
    }

    private static void rejectLegacyIdentityFields(Map<String, Object> request) {
        if (request.containsKey("repoId") || request.containsKey("expectedRevision")) {
            throw new IllegalArgumentException("legacy repository identity fields are not accepted");
        }
    }

    private static void rejectUnknownFields(Map<String, Object> request, ToolProjectionRequirement requirement) {
        Set<String> allowed = allowedFields(requirement.toolName(), requirement.projections().isPresent());
        if (!allowed.containsAll(request.keySet())) {
            throw new IllegalArgumentException("request contains an unknown or irrelevant field");
        }
    }

    private static String requiredText(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    public record GenerationBackedResponse(String repositoryId, String revision, Object result) {
    }

}
