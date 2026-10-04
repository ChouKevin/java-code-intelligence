package com.java.semantic.mcp;

import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideFreshness;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceReadContract.SemanticStatus;
import com.java.semantic.model.source.SourceReadContract.SourceStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Explicit source-only wire schema; the shared application binder remains authoritative. */
public final class SemanticMcpSchemaCatalog {
    private SemanticMcpSchemaCatalog() { }

    public static Map<String, Object> inputSchema(String operation) {
        return switch (operation) {
            case "list_repositories" -> object(Map.of("nameFilter", text(), "limit", integer(1, 100, 20), "cursor", text()), List.of());
            case "get_context" -> object(Map.of("repositoryId", text(), "revision", revision()), List.of("repositoryId"));
            case "list_files" -> object(Map.of("context", context(), "directory", string(), "limit", integer(1, 100, 20),
                    "cursor", text()), List.of("context"));
            case "search_text" -> object(Map.of("context", context(), "query", text(), "directory", string(),
                    "filePattern", text(), "limit", integer(1, 100, 20)), List.of("context", "query"));
            case "read_source" -> object(Map.of("context", context(), "path", text(), "startLine", integer(1, Integer.MAX_VALUE, 1),
                    "maxLines", integer(1, 500, 200), "cursor", text()), List.of("context", "path"));
            default -> throw new IllegalArgumentException("unknown source operation");
        };
    }

    public static Map<String, Object> outputSchema(String operation) {
        return switch (operation) {
            case "list_repositories" -> object(Map.of("items", array(object(Map.of("repositoryId", text(), "displayName", text(),
                    "defaultBranch", text(), "projectGuidePath", nullable(text()), "sourceStatus", enums(SourceStatus.values()),
                    "semanticStatus", enums(SemanticStatus.values()), "revision", nullable(revision())),
                    List.of("repositoryId", "displayName", "defaultBranch", "sourceStatus", "semanticStatus"))),
                    "page", page()), List.of("items", "page"));
            case "get_context" -> object(Map.of("repositoryId", text(), "defaultBranch", text(),
                    "sourceStatus", enums(SourceStatus.values()), "semanticStatus", enums(SemanticStatus.values()),
                    "context", nullable(context()), "projectGuide", nullable(guide()), "coverage", nullable(coverage())),
                    List.of("repositoryId", "defaultBranch", "sourceStatus", "semanticStatus"));
            case "list_files" -> object(Map.of("context", context(), "items", array(object(Map.of("path", text(),
                    "kind", enums(EntryKind.values()), "status", nullable(enums(EntryStatus.values())), "byteLength", nullable(count()),
                    "navigationHint", bool()), List.of("path", "kind", "navigationHint"))), "page", page()),
                    List.of("context", "items", "page"));
            case "search_text" -> object(Map.of("context", context(), "matches", array(object(Map.of("path", text(),
                    "line", count(), "column", count(), "matchedText", string(), "navigationHint", bool()),
                    List.of("path", "line", "column", "matchedText", "navigationHint"))), "truncated", bool(),
                    "scanComplete", bool()), List.of("context", "matches", "truncated", "scanComplete"));
            case "read_source" -> object(Map.of("context", context(), "path", text(), "startLine", count(),
                    "endLine", nullable(count()), "content", string(), "hasMore", bool(), "nextCursor", nullable(text()),
                    "startsMidLine", bool(), "endsMidLine", bool(), "navigationHint", bool()),
                    List.of("context", "path", "startLine", "content", "hasMore", "startsMidLine", "endsMidLine", "navigationHint"));
            default -> throw new IllegalArgumentException("unknown source operation");
        };
    }


    private static Map<String, Object> context() { return object(Map.of("repositoryId", text(), "revision", revision()),
            List.of("repositoryId", "revision")); }
    private static Map<String, Object> page() { return object(Map.of("returned", count(), "hasMore", bool(),
            "nextCursor", nullable(text())), List.of("returned", "hasMore")); }
    private static Map<String, Object> guide() { return object(Map.of("state", enums(GuideState.values()), "path", nullable(text()),
            "digest", nullable(text()), "freshness", enums(GuideFreshness.values())), List.of("state", "freshness")); }
    private static Map<String, Object> coverage() { return object(Map.of("readableFileCount", count(),
            "excludedFileCount", count(), "unsupportedCounts", Map.of("type", "object", "additionalProperties", count())),
            List.of("readableFileCount", "excludedFileCount", "unsupportedCounts")); }
    private static Map<String, Object> text() { return Map.of("type", "string", "minLength", 1); }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> revision() { return Map.of("type", "string", "pattern", "^[a-f0-9]{40}$"); }
    private static Map<String, Object> count() { return Map.of("type", "integer", "minimum", 0); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> integer(int minimum, int maximum, int fallback) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "default", fallback);
    }
    private static Map<String, Object> enums(Enum<?>[] values) {
        return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList());
    }
    private static Map<String, Object> nullable(Map<String, Object> value) {
        return Map.of("anyOf", List.of(value, Map.of("type", "null")));
    }
    private static Map<String, Object> array(Map<String, Object> item) { return Map.of("type", "array", "items", item); }
    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false);
    }
}
