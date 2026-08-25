package com.java.semantic.api;

import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.ToolProjectionCatalog;
import com.java.semantic.model.query.ToolProjectionRequirement;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.PublishedCallGraphService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.PublishedSourceToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** HTTP aliases for the Query catalog; concept endpoints intentionally have no mapping. */
@RestController
@RequestMapping("/v1")
public final class ReadOnlyQueryController {
    private final CurrentRepositoryQueryService repositories;
    private final CodeFactSearchService codeFactSearchService;
    private final CodeFactReadService codeFactReadService;
    private final PublishedCallGraphService callGraphs;
    private final PublishedDiscoveryQueryService discovery;
    private final PublishedEntryPointQueryService entryPoints;
    private final PublishedRelationQueryService relations;
    private final PublishedSourceToolService sourceTools;

    public ReadOnlyQueryController(
            CurrentRepositoryQueryService repositories,
            CodeFactSearchService codeFactSearchService,
            CodeFactReadService codeFactReadService,
            PublishedCallGraphService callGraphs,
            PublishedDiscoveryQueryService discovery,
            PublishedEntryPointQueryService entryPoints,
            PublishedRelationQueryService relations,
            PublishedSourceToolService sourceTools) {
        this.repositories = Objects.requireNonNull(repositories, "repositories are required");
        this.codeFactSearchService = Objects.requireNonNull(codeFactSearchService, "code fact search service is required");
        this.codeFactReadService = Objects.requireNonNull(codeFactReadService, "code fact read service is required");
        this.callGraphs = Objects.requireNonNull(callGraphs, "call graphs are required");
        this.discovery = Objects.requireNonNull(discovery, "discovery is required");
        this.entryPoints = Objects.requireNonNull(entryPoints, "entry points are required");
        this.relations = Objects.requireNonNull(relations, "relations are required");
        this.sourceTools = Objects.requireNonNull(sourceTools, "source tools are required");
    }

    @GetMapping("/repositories")
    public Object listRepositories() {
        return execute("semantic_list_repositories", Map.of());
    }

    @GetMapping("/repositories/{repositoryId}")
    public Object getRepository(@PathVariable String repositoryId) {
        return execute("semantic_get_repository", Map.of("repositoryId", repositoryId));
    }

    @GetMapping("/repositories/{repositoryId}/entry-points")
    public Object listEntryPoints(@PathVariable String repositoryId, @RequestParam Map<String, String> parameters) {
        return execute("semantic_list_entry_points", withRepositoryId(repositoryId, parameters));
    }

    @PostMapping("/analyses/call-graphs/outgoing")
    public Object outgoingCallGraph(@RequestBody Map<String, Object> request) {
        return execute("semantic_analyze_outgoing_call_graph", request);
    }

    @PostMapping("/analyses/call-graphs/incoming")
    public Object incomingCallGraph(@RequestBody Map<String, Object> request) {
        return execute("semantic_analyze_incoming_call_graph", request);
    }

    @PostMapping("/api-routes/lookup")
    public Object lookupRoutes(@RequestBody Map<String, Object> request) {
        return execute("semantic_lookup_api_routes", request);
    }

    @PostMapping("/api-routes/suggest")
    public Object suggestRoutes(@RequestBody Map<String, Object> request) {
        return execute("semantic_suggest_api_routes", request);
    }

    @PostMapping("/discovery/event-listeners")
    public Object eventListeners(@RequestBody Map<String, Object> request) {
        return execute("semantic_discover_event_listeners", request);
    }

    @PostMapping("/discovery/method-implementations")
    public Object methodImplementations(@RequestBody Map<String, Object> request) {
        return execute("semantic_discover_method_implementations", request);
    }

    @PostMapping("/discovery/type-members")
    public Object typeMembers(@RequestBody Map<String, Object> request) {
        return execute("semantic_discover_type_members", request);
    }

    @PostMapping("/discovery/internal-references")
    public Object internalReferences(@RequestBody Map<String, Object> request) {
        return execute("semantic_find_internal_references", request);
    }

    @PostMapping("/discovery/source-symbols/resolve")
    public Object resolveSourceSymbol(@RequestBody Map<String, Object> request) {
        return execute("semantic_resolve_source_symbol", request);
    }

    @PostMapping("/discovery/source-segment")
    public Object sourceSegment(@RequestBody Map<String, Object> request) {
        return execute("semantic_get_source_segment", request);
    }

    @PostMapping("/discovery/method-source")
    public Object methodSource(@RequestBody Map<String, Object> request) {
        return execute("semantic_get_method_source", request);
    }

    @PostMapping("/discovery/evidence-source")
    public Object evidenceSource(@RequestBody Map<String, Object> request) {
        return execute("semantic_get_evidence_source", request);
    }

    private Object execute(String toolName, Map<String, Object> request) {
        ToolProjectionRequirement requirement = ToolProjectionCatalog.requirements().stream()
                .filter(candidate -> toolName.equals(candidate.toolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown Query tool"));
        return QueryMcpToolCatalogConfiguration.execute(requirement, request, repositories, codeFactSearchService,
                codeFactReadService, callGraphs, discovery, entryPoints, relations, sourceTools);
    }

    private static Map<String, Object> withRepositoryId(String repositoryId, Map<String, String> parameters) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.putAll(parameters);
        request.put("repositoryId", repositoryId);
        return Map.copyOf(request);
    }
}
