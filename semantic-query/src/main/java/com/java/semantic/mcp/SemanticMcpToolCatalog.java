package com.java.semantic.mcp;

import java.util.List;

/** The single public name and purpose catalog for Semantic Query MCP tools. */
public final class SemanticMcpToolCatalog {

    private static final List<ToolDefinition> TOOLS = List.of(
            tool("list_repositories", "List visible repositories and their current revisions; no tool must be called first."),
            tool("get_repository", "Read one known repository. Copy repositoryId exactly from Semantic results."),
            tool("search_code", "Search indexed code. Copy repositoryId and revision exactly from Semantic results."),
            tool("get_fact_source", "Expand a returned fact or occurrence. Copy repositoryId, revision, and factId exactly from Semantic results."),
            tool("list_entry_points", "Browse API, messaging, and scheduled entry points. Copy repositoryId and revision exactly from Semantic results."),
            tool("find_api_routes", "Find routes by exact HTTP method and path. Copy repositoryId and revision exactly from Semantic results."),
            tool("find_event_listeners", "Find listeners for an exact fully qualified event type; do not guess the type. Copy repositoryId and revision exactly."),
            tool("list_type_members", "List members of a returned internal type. Copy repositoryId, revision, and typeFactId exactly from Semantic results."),
            tool("find_method_implementations", "Find implementations of a returned method. Copy repositoryId, revision, and methodFactId exactly from Semantic results."),
            tool("find_references", "Find exact indexed references to a returned fact. Copy repositoryId, revision, and factId exactly from Semantic results."),
            tool("find_callers", "Return direct one-hop callers of a returned method. Copy repositoryId, revision, and methodFactId exactly from Semantic results."),
            tool("find_callees", "Return direct one-hop callees of a returned method. Copy repositoryId, revision, and methodFactId exactly from Semantic results."));

    private SemanticMcpToolCatalog() {
    }

    public static List<ToolDefinition> tools() {
        return TOOLS;
    }

    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description);
    }

    public record ToolDefinition(String name, String description) {
    }
}
