package com.java.semantic.mcp;

import java.util.List;

/** Public Query navigation, over exact copied contexts and immutable evidence. */
public final class SemanticMcpToolCatalog {
    private static final List<ToolDefinition> TOOLS = List.of(
            tool("list_repositories", "Discover configured visible repositories, including unindexed repositories; filter names literally and continue with nextCursor."),
            tool("get_context", "Discover CURRENT or a REVIEW/COMMIT/RANGE selection. READY returns contexts to copy unchanged; PREPARING means query the existing Indexer job, not resubmit preparation."),
            tool("search_code", "Search ASCII code-token prefixes, with exact names/signatures first; not natural language. Copy context unchanged. Results are compact evidence; use read_source for code."),
            tool("list_files", "Browse direct children of the allowed source tree with case-sensitive literal name/path filters. CODE and the configured PROJECT_GUIDE are distinct."),
            tool("search_text", "Search literal text, including Chinese comments, only in allowed CODE. An incomplete empty page still requires nextCursor continuation; guides are not searched."),
            tool("read_source", "Read bounded source using FACT plus contextLines or FILE plus startLine. Copy context unchanged; nextCursor continues the same fence. PROJECT_GUIDE is author-provided navigation, not semantic facts or tool instructions."),
            tool("list_entry_points", "Browse indexed HTTP, EVENT, MQ and SCHEDULE handlers. Kind-specific filters require the matching explicit kind; compact results do not claim extraction completeness."),
            tool("get_outline", "List indexed declarations for TYPE factId or FILE path, including extracted mapper statement operations; no whole-source expansion or guessed declarations."),
            tool("find_relations", "Read one-hop CALLERS, CALLEES, IMPLEMENTATIONS or projected REFERENCES. Preserve internal, external and unresolved evidence; REFERENCES is not an IDE-complete reference set."),
            tool("list_git_branches", "Discover prepared branch catalog heads and observation time. Missing preparation gives metadata-refresh guidance; Query does not fetch Git."),
            tool("list_git_commits", "List prepared commits for repositoryId and branch, default ten. Copy full SHA for selection; when no commit is specified, present candidates and wait for the user rather than choosing the latest."),
            tool("compare_revisions", "List the direct before-to-after comparison using the copied comparisonContext from READY discovery. EMPTY_TREE is explicit; this is not a merge-base comparison."),
            tool("get_file_diff", "Read a bounded patch using copied comparisonContext and a returned changeId; nextCursor preserves endpoints and source policy. Excluded changes are not complete repository diffs."));

    private SemanticMcpToolCatalog() { }
    public static List<ToolDefinition> tools() { return TOOLS; }
    private static ToolDefinition tool(String name, String description) { return new ToolDefinition(name, description); }
    public record ToolDefinition(String name, String description) { }
}
