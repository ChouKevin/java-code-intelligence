package com.java.semantic.mcp;

import java.util.List;

/** The single public name and purpose catalog for Semantic Query MCP tools. */
public final class SemanticMcpToolCatalog {

    private static final List<ToolDefinition> TOOLS = List.of(
            tool("list_git_branches", "List branches from one immutable READY Git catalog; responses pin catalogId for later pages."),
            tool("list_git_commits", "List the stored topological/time commit history for one exact READY Git history revision."),
            tool("compare_revisions", "List one immutable direct comparison of exact previous and current Git trees; page.hasMore means request another page."),
            tool("get_file_diff", "Read a bounded JGit-produced patch for one returned comparison change; nextCursor continues the same exact endpoints."),
            tool("list_files", "List direct entries in one exact READY Git snapshot directory, with sealed inventory coverage by content status."),
            tool("read_file", "Read a bounded UTF-8 text page from one exact READY Git snapshot file; nextCursor resumes the same raw path."),
            tool("search_text", "Search literal text in one exact READY Git snapshot; an incomplete empty page returns nextCursor to continue the bounded scan."),
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
            tool("find_callees", "Return direct one-hop callees of a returned method. resolutionStatus is INDEXED for internal targets, UNRESOLVED for unresolved calls, and UNINDEXED_TARGET for other typed external targets; it does not confirm external availability. Copy repositoryId, revision, and methodFactId exactly; page.hasMore means request another page."),
            tool("get_review", "Discover one READY immutable review and its opaque A/B evidence metadata and authorized coverage."),
            tool("review_search_code", "Search code evidence on one exact READY review side; copy repositoryId, reviewId, side, and revision exactly."),
            tool("review_get_fact_source", "Expand one returned fact to exact indexed source on one exact READY review side."),
            tool("review_list_entry_points", "Browse indexed entry points on one exact READY review side."),
            tool("review_find_api_routes", "Find exact HTTP routes on one exact READY review side."),
            tool("review_find_event_listeners", "Find listeners for one exact event type on one exact READY review side."),
            tool("review_list_type_members", "List members of a returned type on one exact READY review side."),
            tool("review_find_method_implementations", "Find direct implementations or overrides on one exact READY review side."),
            tool("review_find_references", "Find exact references on one exact READY review side."),
            tool("review_find_callers", "Find direct callers on one exact READY review side."),
            tool("review_find_callees", "Find direct callees on one exact READY review side."));

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
