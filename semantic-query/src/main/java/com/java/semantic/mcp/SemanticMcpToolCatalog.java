package com.java.semantic.mcp;

import java.util.List;

/** Only the five immutable published-source operations are exposed. */
public final class SemanticMcpToolCatalog {
    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("list_repositories", "List allowed repositories and their source preparation status."),
            new ToolDefinition("get_context", "Resolve an allowed repository's published source revision."),
            new ToolDefinition("list_files", "List direct children of a published source directory."),
            new ToolDefinition("search_text", "Search literal case-sensitive text in a published source revision."),
            new ToolDefinition("read_source", "Read a bounded lossless page of published source text."));

    private SemanticMcpToolCatalog() { }

    public static List<ToolDefinition> tools() { return TOOLS; }

    public record ToolDefinition(String name, String description) { }
}
