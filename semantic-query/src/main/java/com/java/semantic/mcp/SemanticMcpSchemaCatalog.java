package com.java.semantic.mcp;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.git.GitComparisonPolicyCoverage;
import com.java.semantic.query.application.SemanticQueryContract;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit wire schemas: unions reject unrelated identities and never advertise aliases. */
public final class SemanticMcpSchemaCatalog {
    private SemanticMcpSchemaCatalog() { }

    public static Map<String, Object> inputSchema(String operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> required;
        switch (operation) {
            case "list_repositories" -> { fields.put("nameFilter", text()); required = List.of(); }
            case "get_context" -> {
                fields.put("repositoryId", text()); fields.put("selector", selector());
                fields.put("limit", integer(1, 100, 20));
                return object(fields, List.of("repositoryId", "selector"));
            }
            case "search_code" -> {
                fields.put("context", context()); fields.put("query", Map.of("type", "string", "minLength", 2, "maxLength", 256)); fields.put("kinds", kinds());
                fields.put("packagePrefix", packagePrefix()); fields.put("path", text()); required = List.of("context", "query");
            }
            case "list_files" -> {
                fields.put("context", context()); fields.put("directory", string());
                fields.put("nameFilter", text()); fields.put("pathFilter", text()); required = List.of("context");
            }
            case "search_text" -> {
                fields.put("context", context()); fields.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 256,
                        "pattern", "^[^\\r\\n]+$", "description", "One literal line, at most 256 Unicode code points."));
                fields.put("directory", string()); required = List.of("context", "query");
            }
            case "read_source" -> {
                fields.put("context", context());
                fields.put("target", union(object(Map.of("kind", constant("FACT"), "factId", text(),
                                "contextLines", integer(0, 20, 0)), List.of("kind", "factId")),
                        object(Map.of("kind", constant("FILE"), "path", text(), "startLine", integer(1, Integer.MAX_VALUE, 1)),
                                List.of("kind", "path"))));
                fields.put("maxLines", integer(1, 500, 200)); fields.put("cursor", text());
                return object(fields, List.of("context", "target"));
            }
            case "list_entry_points" -> {
                fields.put("context", context()); fields.put("kind", enumeration("HTTP", "EVENT", "MQ", "SCHEDULE"));
                fields.put("handlerName", text()); fields.put("packagePrefix", packagePrefix());
                fields.put("httpMethod", enums(SemanticQueryContract.HttpMethod.values())); fields.put("path", text());
                fields.put("eventType", text()); fields.put("destination", destination()); fields.put("trigger", text());
                required = List.of("context");
            }
            case "get_outline" -> {
                fields.put("context", context()); fields.put("target", union(
                        object(Map.of("kind", constant("TYPE"), "factId", text()), List.of("kind", "factId")),
                        object(Map.of("kind", constant("FILE"), "path", text()), List.of("kind", "path"))));
                fields.put("kinds", Map.of("type", "array", "uniqueItems", true, "items",
                        enumeration("TYPE", "METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT", "MAPPER_STATEMENT")));
                required = List.of("context", "target");
            }
            case "find_relations" -> {
                fields.put("context", context()); fields.put("relation", enums(SemanticQueryContract.RelationMode.values()));
                fields.put("factId", text()); required = List.of("context", "relation", "factId");
            }
            case "list_git_branches" -> { fields.put("repositoryId", text()); required = List.of("repositoryId"); }
            case "list_git_commits" -> {
                fields.put("repositoryId", text()); fields.put("branch", text()); required = List.of("repositoryId", "branch");
            }
            case "compare_revisions" -> { fields.put("comparisonContext", comparison()); required = List.of("comparisonContext"); }
            case "get_file_diff" -> {
                return object(Map.of("comparisonContext", comparison(), "changeId", text(), "cursor", text()),
                        List.of("comparisonContext", "changeId"));
            }
            default -> throw new IllegalArgumentException("unknown Semantic operation");
        }
        fields.put("cursor", text()); fields.put("limit", integer(1, 100, operation.equals("list_git_commits") ? 10 : 20));
        Map<String, Object> schema = new LinkedHashMap<>(object(fields, required));
        if (operation.equals("list_entry_points")) schema.put("allOf", List.of(
                filterKind("httpMethod", "HTTP"), filterKind("path", "HTTP"), filterKind("eventType", "EVENT"),
                filterKind("destination", "MQ"), filterKind("trigger", "SCHEDULE")));
        return schema;
    }

    public static Map<String, Object> outputSchema(String operation) {
        if (!SemanticQueryContract.OPERATIONS.contains(operation)) throw new IllegalArgumentException("unknown Semantic operation");
        Map<String, Object> success = switch (operation) {
            case "list_repositories" -> object(Map.of("items", array(object(Map.of("repositoryId", text(), "displayName", string(),
                    "defaultBranch", text(), "configured", bool(), "publishedRevision", revision()),
                    List.of("repositoryId", "displayName", "defaultBranch", "configured"))), "page", page()), List.of("items", "page"));
            case "get_context" -> contextResult();
            case "search_code", "get_outline" -> collection(fact());
            case "list_files" -> collection(object(Map.of("path", text(), "entryType", enumeration("FILE", "DIRECTORY"),
                    "contentKind", enumeration("CODE", "PROJECT_GUIDE"), "contentStatus", text(), "byteLength", count(),
                    "projectGuide", guide()), List.of("path", "entryType")));
            case "search_text" -> extend(collection(object(Map.of("path", text(), "range", range(), "snippet", string(),
                    "snippetTruncated", bool()), List.of("path", "range", "snippet", "snippetTruncated"))), Map.of("scanComplete", bool()), List.of("scanComplete"));
            case "read_source" -> source();
            case "list_entry_points" -> collection(object(Map.of("kind", enums(SemanticQueryContract.EntryKind.values()), "factId", text(),
                    "handler", fact(), "trigger", object(Map.of("httpMethod", enums(SemanticQueryContract.HttpMethod.values()), "path", text(),
                    "eventType", text(), "destination", destination(), "trigger", text()), List.of())), List.of("kind", "handler", "trigger")));
            case "find_relations" -> relations();
            case "list_git_branches", "list_git_commits" -> metadataCollection(operation);
            case "compare_revisions" -> object(Map.of("comparisonContext", comparison(), "ancestry", text(), "items", array(change()), "page", page(),
                    "policyCoverage", policyCoverage()), List.of("comparisonContext", "ancestry", "items", "page", "policyCoverage"));
            case "get_file_diff" -> object(Map.of("comparisonContext", comparison(), "change", change(), "patch", string(), "complete", bool(), "nextCursor", text()),
                    List.of("comparisonContext", "change", "complete"));
            default -> throw new IllegalArgumentException("unknown Semantic operation");
        };
        return Map.of("type", "object", "oneOf", List.of(success, error()));
    }

    public static Set<String> allowedFields(String operation) { return properties(inputSchema(operation)).keySet(); }
    public static List<String> requiredFields(String operation) {
        Object required = inputSchema(operation).get("required");
        if (!(required instanceof List<?> values)) throw new IllegalStateException("invalid schema");
        return values.stream().map(String.class::cast).toList();
    }
    private static Map<String, Object> context() {
        return union(object(Map.of("kind", constant("CURRENT"), "repositoryId", text(), "revision", revision()), List.of("kind", "repositoryId", "revision")),
                object(Map.of("kind", constant("REVIEW"), "repositoryId", text(), "revision", revision(), "reviewId", text(),
                        "side", enumeration("BEFORE", "AFTER")), List.of("kind", "repositoryId", "revision", "reviewId", "side")));
    }
    private static Map<String, Object> selector() {
        return union(object(Map.of("kind", constant("CURRENT")), List.of("kind")),
                object(Map.of("kind", constant("REVIEW"), "reviewId", text()), List.of("kind", "reviewId")),
                object(Map.of("kind", constant("COMMIT"), "revision", revision()), List.of("kind", "revision")),
                object(Map.of("kind", constant("RANGE"), "beforeRevision", revision(), "afterRevision", revision()),
                        List.of("kind", "beforeRevision", "afterRevision")));
    }
    private static Map<String, Object> endpoint() {
        return union(object(Map.of("kind", constant("EMPTY_TREE")), List.of("kind")),
                object(Map.of("kind", constant("REVISION"), "revision", revision()), List.of("kind", "revision")));
    }
    private static Map<String, Object> comparison() {
        return object(Map.of("repositoryId", text(), "reviewId", text(), "before", endpoint(), "after",
                object(Map.of("kind", constant("REVISION"), "revision", revision()), List.of("kind", "revision"))),
                List.of("repositoryId", "reviewId", "before", "after"));
    }
    private static Map<String, Object> destination() { return object(Map.of("broker", text(), "destination", text()), List.of("broker", "destination")); }
    private static Map<String, Object> page() { return object(Map.of("returned", count(), "hasMore", bool(), "nextCursor", text()), List.of("returned", "hasMore")); }
    private static Map<String, Object> position() { return object(Map.of("line", count(), "character", count()), List.of("line", "character")); }
    private static Map<String, Object> range() { return object(Map.of("start", position(), "end", position()), List.of("start", "end")); }
    private static Map<String, Object> fact() {
        return object(Map.of("factId", text(), "kind", enums(CodeFactKind.values()), "displayName", string(), "signature", string(),
                "path", text(), "range", range(), "canonical", text(), "mapperStatementKind", enumeration("SELECT", "INSERT", "UPDATE", "DELETE", "ANNOTATION")),
                List.of("factId", "kind", "displayName", "path", "range"));
    }
    private static Map<String, Object> collection(Map<String, Object> item) { return object(Map.of("context", context(), "items", array(item), "page", page()), List.of("context", "items", "page")); }
    private static Map<String, Object> source() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("context", context()); fields.put("target", properties(inputSchema("read_source")).get("target"));
        fields.put("path", text()); fields.put("contentKind", enumeration("CODE", "PROJECT_GUIDE")); fields.put("contentStatus", text());
        fields.put("content", string()); fields.put("factRange", range()); fields.put("window", object(Map.of("start", position(), "end", position()), List.of("start")));
        fields.put("pageRange", range()); fields.put("rangeComplete", bool()); fields.put("startLineComplete", bool()); fields.put("endLineComplete", bool());
        fields.put("nextCursor", text()); fields.put("projectGuide", guide());
        return object(fields, List.of("context", "target", "path", "contentKind", "contentStatus", "rangeComplete", "startLineComplete", "endLineComplete"));
    }
    private static Map<String, Object> relations() {
        Map<String, Object> external = object(Map.of("kind", text(), "displayName", string(), "canonical", text(), "arity", count()), List.of("kind", "displayName"));
        Map<String, Object> target = object(Map.of("resolution", enumeration("INTERNAL", "EXTERNAL", "UNRESOLVED"), "fact", fact(), "external", external), List.of("resolution"));
        Map<String, Object> occurrence = object(Map.of("factId", text(), "path", text(), "range", range()), List.of("factId", "path", "range"));
        return extend(collection(object(Map.of("relationKind", text(), "origin", fact(), "target", target, "occurrence", occurrence),
                List.of("relationKind", "origin", "target", "occurrence"))), Map.of("relation", enums(SemanticQueryContract.RelationMode.values()),
                "evidenceScope", enums(SemanticQueryContract.RelationEvidenceScope.values())), List.of("relation", "evidenceScope"));
    }
    private static Map<String, Object> metadataCollection(String operation) {
        Map<String, Object> metadata = object(Map.of("jobId", text(), "catalogId", text(), "historyId", text(), "branch", text(),
                "headRevision", revision(), "observedAt", timestamp()), List.of("jobId", "catalogId", "historyId", "branch", "headRevision", "observedAt"));
        Map<String, Object> item = operation.equals("list_git_branches")
                ? object(Map.of("branch", text(), "headRevision", revision()), List.of("branch", "headRevision"))
                : object(Map.of("revision", revision(), "shortRevision", text(), "parents", array(revision()), "subject", string(), "committedAt", timestamp()),
                        List.of("revision", "shortRevision", "parents", "subject", "committedAt"));
        return object(Map.of("repositoryId", text(), "metadata", metadata, "items", array(item), "page", page()), List.of("repositoryId", "metadata", "items", "page"));
    }
    private static Map<String, Object> policyCoverage() {
        return object(Map.of("excludedChanges", count(), "reasons", array(object(Map.of(
                "reason", enums(GitComparisonPolicyCoverage.Reason.values()),
                "count", Map.of("type", "integer", "minimum", 1)), List.of("reason", "count")))),
                List.of("excludedChanges", "reasons"));
    }
    private static Map<String, Object> change() {
        Map<String, Object> side = object(Map.of("path", text(), "mode", text(), "blobId", text(), "contentKind", enumeration("CODE", "PROJECT_GUIDE"),
                "projectGuide", guide()), List.of("path", "mode", "blobId", "contentKind"));
        return object(Map.of("changeId", text(), "kind", text(), "before", side, "after", side, "diffStatus", text()), List.of("changeId", "kind", "diffStatus"));
    }
    private static Map<String, Object> guide() {
        Map<String, Object> scope = object(Map.of("includedPaths", array(string()), "excludedPaths", array(string()), "limitations", array(string())),
                List.of("includedPaths", "excludedPaths", "limitations"));
        Map<String, Object> provenance = object(Map.of("formatVersion", count(), "promptVersion", count(), "repositoryId", text(), "analyzedRevision", revision(),
                "generatedAt", timestamp(), "sourceScope", scope), List.of("formatVersion", "promptVersion", "repositoryId", "analyzedRevision", "generatedAt", "sourceScope"));
        return object(Map.of("state", enumeration("DISABLED", "ABSENT", "INVALID", "AVAILABLE"), "reason", string(), "path", text(), "digest", text(),
                "importedRevision", revision(), "provenance", provenance, "freshness", text()), List.of("state", "freshness"));
    }
    private static Map<String, Object> overview() {
        Map<String, Object> coverage = object(Map.of("scope", enumeration("GENERATION", "AUTHORIZED_SOURCE_FILES"), "readableCode", count(), "excludedOrUnsupported", count(),
                "extractionIssues", count(), "unresolvedSemanticEvidence", count(), "omittedMetrics", array(text())), List.of("scope", "readableCode", "extractionIssues", "omittedMetrics"));
        Map<String, Object> module = object(Map.of("path", string(), "sourceRoots", bounded(string())), List.of("path", "sourceRoots"));
        Map<String, Object> pkg = object(Map.of("name", string(), "sourceCount", count()), List.of("name", "sourceCount"));
        Map<String, Object> entry = object(Map.of("kind", enums(SemanticQueryContract.EntryKind.values()), "count", count()), List.of("kind", "count"));
        return object(Map.of("modules", bounded(module), "sourceRoots", bounded(string()), "packages", bounded(pkg), "entryPoints", bounded(entry),
                "uncountedEntryKinds", array(enums(SemanticQueryContract.EntryKind.values())), "coverage", coverage),
                List.of("modules", "sourceRoots", "packages", "entryPoints", "uncountedEntryKinds", "coverage"));
    }
    private static Map<String, Object> bounded(Map<String, Object> item) { return object(Map.of("items", array(item), "omitted", bool()), List.of("items", "omitted")); }
    private static Map<String, Object> contextResult() {
        Map<String, Object> common = Map.of("repositoryId", text(), "selector", selector(), "state", enums(SemanticQueryContract.ContextState.values()));
        Map<String, Object> current = new LinkedHashMap<>(common);
        current.put("selector", object(Map.of("kind", constant("CURRENT")), List.of("kind")));
        current.put("configuredBranch", text()); current.put("activeJob", object(Map.of("jobId", text(), "operation", text(), "phase", text(), "requestId", text()), List.of("jobId", "operation", "phase")));
        current.put("revision", revision()); current.put("indexedAt", timestamp()); current.put("preparationBranch", text()); current.put("context", context());
        current.put("overview", overview()); current.put("projectGuide", guide());
        Map<String, Object> review = new LinkedHashMap<>(common);
        review.put("selector", union(
                object(Map.of("kind", constant("REVIEW"), "reviewId", text()), List.of("kind", "reviewId")),
                object(Map.of("kind", constant("COMMIT"), "revision", revision()), List.of("kind", "revision")),
                object(Map.of("kind", constant("RANGE"), "beforeRevision", revision(), "afterRevision", revision()),
                        List.of("kind", "beforeRevision", "afterRevision"))));
        Map<String, Object> side = object(Map.of("kind", enumeration("EMPTY_TREE", "REVISION"), "revision", revision(), "context", context(), "overview", overview(), "projectGuide", guide()), List.of("kind"));
        review.put("reviewId", text()); review.put("jobId", text()); review.put("failureCategory", text()); review.put("before", side); review.put("after", side); review.put("comparisonContext", comparison());
        return union(object(current, List.of("repositoryId", "selector", "state")), object(review, List.of("repositoryId", "selector", "state")));
    }
    private static Map<String, Object> error() { return object(Map.of("code", text(), "message", string(), "retryable", bool(), "currentRevision", revision()), List.of("code", "message", "retryable")); }
    private static Map<String, Object> text() { return Map.of("type", "string", "minLength", 1); }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> revision() { return Map.of("type", "string", "pattern", "^[a-f0-9]{40}$"); }
    private static Map<String, Object> packagePrefix() {
        return Map.of("type", "string", "pattern", "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    }
    private static Map<String, Object> filterKind(String field, String kind) {
        return Map.of("if", Map.of("required", List.of(field)),
                "then", Map.of("required", List.of("kind"), "properties", Map.of("kind", constant(kind))));
    }
    private static Map<String, Object> timestamp() { return Map.of("type", "string", "format", "date-time"); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> count() { return Map.of("type", "integer", "minimum", 0); }
    private static Map<String, Object> integer(int minimum, int maximum, int fallback) { return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "default", fallback); }
    private static Map<String, Object> constant(String value) { return Map.of("type", "string", "const", value); }
    private static Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private static Map<String, Object> enums(Enum<?>[] values) { return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList()); }
    private static Map<String, Object> kinds() { return Map.of("type", "array", "items", enums(CodeFactKind.values()), "uniqueItems", true); }
    private static Map<String, Object> array(Map<String, Object> item) { return Map.of("type", "array", "items", item); }
    @SafeVarargs
    private static Map<String, Object> union(Map<String, Object>... alternatives) { return Map.of("oneOf", List.of(alternatives)); }
    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) { return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false); }
    private static Map<String, Object> extend(Map<String, Object> schema, Map<String, Object> fields, List<String> required) {
        Map<String, Object> merged = new LinkedHashMap<>(properties(schema)); merged.putAll(fields);
        Object value = schema.get("required");
        if (!(value instanceof List<?> existing)) throw new IllegalStateException("invalid schema");
        List<String> all = java.util.stream.Stream.concat(existing.stream().map(String.class::cast), required.stream()).toList();
        return object(merged, all);
    }
    private static Map<String, Object> properties(Map<String, Object> schema) {
        Object value = schema.get("properties");
        if (!(value instanceof Map<?, ?> fields)) throw new IllegalStateException("invalid schema");
        Map<String, Object> result = new LinkedHashMap<>(); fields.forEach((key, field) -> result.put(String.class.cast(key), field));
        return result;
    }
}
