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
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.PublishedCallGraphService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.PublishedSourceToolService;
import com.java.semantic.query.application.RevisionOutdatedException;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

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

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> mcpQueryToolSpecifications(
            CurrentRepositoryQueryService repositories,
            CodeFactSearchService codeFactSearchService,
            CodeFactReadService codeFactReadService,
            PublishedCallGraphService callGraphs,
            PublishedDiscoveryQueryService discovery,
            PublishedEntryPointQueryService entryPoints,
            PublishedRelationQueryService relations,
            PublishedSourceToolService sourceTools) {
        return ToolProjectionCatalog.requirements().stream()
                .map(requirement -> specification(requirement, repositories, codeFactSearchService, codeFactReadService,
                        callGraphs, discovery, entryPoints, relations, sourceTools))
                .toList();
    }

    private static McpStatelessServerFeatures.SyncToolSpecification specification(
            ToolProjectionRequirement requirement,
            CurrentRepositoryQueryService repositories,
            CodeFactSearchService codeFactSearchService,
            CodeFactReadService codeFactReadService,
            PublishedCallGraphService callGraphs,
            PublishedDiscoveryQueryService discovery,
            PublishedEntryPointQueryService entryPoints,
            PublishedRelationQueryService relations,
            PublishedSourceToolService sourceTools) {
        McpSchema.Tool tool = McpSchema.Tool.builder(requirement.toolName())
                .description("Read persisted semantic index facts only")
                .inputSchema(inputSchema(requirement))
                .outputSchema(Map.of("type", "object"))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .build())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> invoke(requirement, request.arguments(), repositories,
                        codeFactSearchService, codeFactReadService, callGraphs, discovery, entryPoints, relations, sourceTools))
                .build();
    }

    private static Map<String, Object> inputSchema(ToolProjectionRequirement requirement) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repositoryId", Map.of("type", "string"));
        if (requirement.projections().isPresent()) {
            properties.put("revision", Map.of("type", "string"));
        }
        if ("semantic_search_code_facts".equals(requirement.toolName())) {
            properties.put("query", Map.of("type", "string"));
        }
        if ("semantic_get_code_fact".equals(requirement.toolName())) {
            properties.put("factId", Map.of("type", "string"));
        }
        properties.put("packageName", Map.of("type", "string"));
        properties.put("className", Map.of("type", "string"));
        properties.put("sourceFile", Map.of("type", "string"));
        properties.put("methodName", Map.of("type", "string"));
        properties.put("parameterTypes", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("kinds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("eventType", Map.of("type", "string"));
        properties.put("symbol", Map.of("type", "string"));
        properties.put("httpMethod", Map.of("type", "string"));
        properties.put("path", Map.of("type", "string"));
        properties.put("offset", Map.of("type", "integer", "minimum", 0));
        properties.put("limit", Map.of("type", "integer", "minimum", 1));
        properties.put("depth", Map.of("type", "integer", "minimum", 1, "maximum", 2));
        properties.put("depthTwoNodeBudget", Map.of("type", "integer", "minimum", 0));
        properties.put("line", Map.of("type", "integer", "minimum", 0));
        properties.put("character", Map.of("type", "integer", "minimum", 0));
        properties.put("startLine", Map.of("type", "integer", "minimum", 0));
        properties.put("startCharacter", Map.of("type", "integer", "minimum", 0));
        properties.put("endLine", Map.of("type", "integer", "minimum", 0));
        properties.put("endCharacter", Map.of("type", "integer", "minimum", 0));
        List<String> required = requiredFields(requirement.toolName(), requirement.projections().isPresent());
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
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

    private static McpSchema.CallToolResult invoke(
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
        try {
            Object response = execute(requirement, arguments, repositories, codeFactSearchService, codeFactReadService,
                    callGraphs, discovery, entryPoints, relations, sourceTools);
            return McpSchema.CallToolResult.builder().addTextContent("Query completed")
                    .structuredContent(response).isError(false).build();
        } catch (RevisionOutdatedException exception) {
            return failure(Map.of(
                    "code", "REVISION_OUTDATED",
                    "repositoryId", exception.repositoryId().value(),
                    "requestedRevision", exception.requestedRevision().value(),
                    "currentRevision", exception.currentRevision().value(),
                    "retryGuidance", "Retry with currentRevision."));
        } catch (RuntimeException exception) {
            return failure(Map.of("code", "REQUEST_INVALID", "message", "request is invalid"));
        }
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
        return switch (requirement.toolName()) {
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
    }

    private static CodeFactSearchResult searchCodeFacts(Map<String, Object> request, CodeFactSearchService service) {
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredText(request, "repositoryId")),
                new RepositoryRevision(requiredText(request, "revision")), requiredText(request, "query"),
                java.util.Set.of(), java.util.Optional.empty(), CodeFactSearchQuery.DEFAULT_OFFSET, CodeFactSearchQuery.DEFAULT_LIMIT);
        return service.search(query);
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

    private static String requiredText(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private static McpSchema.CallToolResult failure(Map<String, String> body) {
        return McpSchema.CallToolResult.builder().addTextContent(body.toString()).isError(true).build();
    }
}
