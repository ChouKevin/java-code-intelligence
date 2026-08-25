package com.java.semantic.mcp;

import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.query.ToolProjectionRequirement;

import java.util.List;
import java.util.Set;

/** The authoritative, finite read-only MCP catalog and its persisted-data contract. */
public final class ToolProjectionCatalog {
    private static final List<ToolProjectionRequirement> REQUIREMENTS = List.of(
            ToolProjectionRequirement.generation("semantic_analyze_incoming_call_graph", projections(ProjectionName.RELATIONS, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_analyze_outgoing_call_graph", projections(ProjectionName.RELATIONS, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_discover_event_listeners", projections(ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_discover_method_implementations", projections(ProjectionName.RELATIONS, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_discover_type_members", projections(ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_find_internal_references", projections(ProjectionName.RELATIONS, ProjectionName.SYMBOLS)),
            // Code-fact readers first select SEARCH, then derive the only required authoritative projection from the stored kind.
            ToolProjectionRequirement.generation("semantic_get_code_fact", projections(ProjectionName.SEARCH)),
            ToolProjectionRequirement.generation("semantic_get_evidence_source", projections(ProjectionName.SOURCES, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_get_method_source", projections(ProjectionName.SOURCES, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.metadata("semantic_get_repository"),
            ToolProjectionRequirement.generation("semantic_get_source_segment", projections(ProjectionName.SOURCES, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_list_entry_points", projections(ProjectionName.ENTRY_POINTS, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.metadata("semantic_list_repositories"),
            ToolProjectionRequirement.generation("semantic_lookup_api_routes", projections(ProjectionName.ENTRY_POINTS, ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_resolve_source_symbol", projections(ProjectionName.SYMBOLS)),
            ToolProjectionRequirement.generation("semantic_search_code_facts", projections(ProjectionName.SEARCH)),
            ToolProjectionRequirement.generation("semantic_suggest_api_routes", projections(ProjectionName.ENTRY_POINTS, ProjectionName.SYMBOLS)));

    private ToolProjectionCatalog() {
    }

    static {
        Set<ProjectionName> current = IndexSchemaContract.requiredProjectionVersions().keySet().stream()
                .map(ProjectionName::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (ToolProjectionRequirement requirement : REQUIREMENTS) {
            requirement.projections().ifPresent(projections -> {
                if (!current.containsAll(projections.names())) {
                    throw new IllegalStateException("tool requires a projection outside the current index contract");
                }
            });
        }
    }

    public static List<ToolProjectionRequirement> requirements() {
        return REQUIREMENTS;
    }

    public static List<String> toolNames() {
        return REQUIREMENTS.stream().map(ToolProjectionRequirement::toolName).toList();
    }

    private static ProjectionRequirements projections(ProjectionName first, ProjectionName second) {
        return new ProjectionRequirements(Set.of(first, second));
    }

    private static ProjectionRequirements projections(ProjectionName projection) {
        return new ProjectionRequirements(Set.of(projection));
    }
}
