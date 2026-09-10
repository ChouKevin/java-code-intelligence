package com.java.semantic.mcp;

import java.util.List;

/** The single public name and purpose catalog for Semantic Query MCP tools. */
public final class SemanticMcpToolCatalog {

    private static final List<ToolDefinition> TOOLS = List.of(
            tool("list_repositories", "List visible repositories and their current revisions. If a later call reports REVISION_OUTDATED, read the current revision and acquire fresh returned IDs."),
            tool("get_repository", "Read one known repository and its current revision. Copy repositoryId exactly from Semantic results."),
            tool("search_code", "Search ASCII alphanumeric code-name tokens by prefix, not natural language. Copy repositoryId and revision exactly; page.hasMore means request another page. Empty items mean no matching indexed evidence."),
            tool("get_fact_source", "Expand a returned fact or occurrence to exact indexed source. Copy repositoryId, revision, and factId exactly; unknown or unsupported facts return errors."),
            tool("list_entry_points", "Browse indexed API, messaging, and scheduled entry points. Copy repositoryId and revision exactly; page.hasMore means request another page."),
            tool("find_api_routes", "Find indexed routes by exact HTTP method and path. Copy repositoryId and revision exactly; page.hasMore means request another page."),
            tool("find_event_listeners", "Find listeners for an exact fully qualified event type. Copy repositoryId and revision exactly; page.hasMore means request another page."),
            tool("list_type_members", "List members of a returned internal type. Copy repositoryId, revision, and typeFactId exactly; page.hasMore means request another page."),
            tool("find_method_implementations", "Find indexed implementations or overrides of a returned method. Copy repositoryId, revision, and methodFactId exactly; page.hasMore means request another page."),
            tool("find_references", "Find exact indexed references to a returned declaration. Copy repositoryId, revision, and factId exactly; page.hasMore means request another page."),
            tool("find_callers", "Return direct one-hop callers of a returned method. Copy repositoryId, revision, and methodFactId exactly; page.hasMore means request another page."),
            tool("find_callees", "Return direct one-hop callees of a returned method. Copy repositoryId, revision, and methodFactId exactly; page.hasMore means request another page."));

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
