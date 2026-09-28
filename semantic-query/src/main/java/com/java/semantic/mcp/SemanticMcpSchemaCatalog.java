package com.java.semantic.mcp;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.application.SemanticQueryContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The single MCP SDK schema-map catalog when generated schemas cannot express this contract. */
public final class SemanticMcpSchemaCatalog {

    private static final Map<String, Map<String, Object>> INPUT_SCHEMAS = inputSchemas();
    private static final Map<String, Map<String, Object>> OUTPUT_SCHEMAS = outputSchemas();

    private SemanticMcpSchemaCatalog() {
    }

    public static Map<String, Object> inputSchema(String toolName) {
        return Optional.ofNullable(INPUT_SCHEMAS.get(toolName))
                .orElseThrow(() -> new IllegalArgumentException("unknown Semantic MCP tool"));
    }

    public static Map<String, Object> outputSchema(String toolName) {
        return Optional.ofNullable(OUTPUT_SCHEMAS.get(toolName))
                .orElseThrow(() -> new IllegalArgumentException("unknown Semantic MCP tool"));
    }

    public static Set<String> allowedFields(String toolName) {
        return properties(inputSchema(toolName)).keySet();
    }

    @SuppressWarnings("unchecked")
    public static List<String> requiredFields(String toolName) {
        return (List<String>) inputSchema(toolName).get("required");
    }

    private static Map<String, Map<String, Object>> outputSchemas() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("list_git_branches", gitBranchCollection());
        schemas.put("list_git_commits", gitCommitCollection());
        schemas.put("compare_revisions", gitComparisonCollection());
        schemas.put("get_file_diff", gitFileDiffResult());
        schemas.put("list_files", gitFileCollection());
        schemas.put("read_file", gitFileContent());
        schemas.put("search_text", gitTextSearchResult());
        schemas.put("list_repositories", repositoryCollection());
        schemas.put("get_repository", repositoryItem());
        schemas.put("search_code", searchCodeResult());
        schemas.put("get_fact_source", factSourceResult());
        schemas.put("list_entry_points", collection(entryPointItem()));
        schemas.put("find_api_routes", collection(entryPointItem()));
        schemas.put("find_event_listeners", collection(eventListenerItem()));
        schemas.put("list_type_members", collection(internalProgramElement()));
        schemas.put("find_method_implementations", collection(implementationItem()));
        schemas.put("find_references", collection(referenceItem()));
        schemas.put("find_callers", collection(callerItem()));
        schemas.put("find_callees", collection(calleeItem()));
        schemas.put("get_review", reviewDetails());
        schemas.put("review_search_code", reviewResult(searchCodeResult()));
        schemas.put("review_get_fact_source", reviewResult(factSourceResult()));
        schemas.put("review_list_entry_points", reviewResult(collection(entryPointItem())));
        schemas.put("review_find_api_routes", reviewResult(collection(entryPointItem())));
        schemas.put("review_find_event_listeners", reviewResult(collection(eventListenerItem())));
        schemas.put("review_list_type_members", reviewResult(collection(internalProgramElement())));
        schemas.put("review_find_method_implementations", reviewResult(collection(implementationItem())));
        schemas.put("review_find_references", reviewResult(collection(referenceItem())));
        schemas.put("review_find_callers", reviewResult(collection(callerItem())));
        schemas.put("review_find_callees", reviewResult(collection(calleeItem())));
        return Map.copyOf(schemas);
    }

    private static Map<String, Object> repositoryCollection() {
        return schema(Map.of("items", items(repositoryItem()), "page", page()), List.of("items", "page"));
    }

    private static Map<String, Object> gitBranchCollection() {
        Map<String, Object> item = schema(Map.of("branch", string(), "head", revision()), List.of("branch", "head"));
        return schema(Map.of("repositoryId", repositoryId(), "catalogId", gitEvidenceId(), "observedAt", string(),
                "items", items(item), "page", page()), List.of("repositoryId", "catalogId", "observedAt", "items", "page"));
    }

    private static Map<String, Object> gitCommitCollection() {
        Map<String, Object> item = schema(Map.of("revision", revision(), "parents", items(revision()), "subject", string(),
                "committedAt", string()), List.of("revision", "parents", "subject", "committedAt"));
        return schema(Map.of("repositoryId", repositoryId(), "historyId", gitEvidenceId(), "revision", revision(), "preparedAt", string(),
                "items", items(item), "page", page()), List.of("repositoryId", "historyId", "revision", "preparedAt", "items", "page"));
    }

    private static Map<String, Object> gitComparisonCollection() {
        return schema(Map.of("repositoryId", repositoryId(), "comparisonId", gitEvidenceId(), "previous", revision(), "current", revision(),
                "previousSnapshotId", gitEvidenceId(), "currentSnapshotId", gitEvidenceId(), "ancestry", string(), "items", items(gitChange()), "page", page()),
                List.of("repositoryId", "comparisonId", "previous", "current", "previousSnapshotId", "currentSnapshotId", "ancestry", "items", "page"));
    }

    private static Map<String, Object> gitFileDiffResult() {
        return schema(Map.of("repositoryId", repositoryId(), "comparisonId", gitEvidenceId(), "previous", revision(), "current", revision(),
                "change", gitChange(), "patch", string(), "nextCursor", string()),
                List.of("repositoryId", "comparisonId", "previous", "current", "change", "patch"));
    }

    private static Map<String, Object> gitFileCollection() {
        return schema(Map.of("repositoryId", repositoryId(), "snapshotId", gitEvidenceId(), "revision", revision(), "items", items(gitFileItem()),
                "page", page(), "coverage", gitSnapshotCoverage()), List.of("repositoryId", "snapshotId", "revision", "items", "page", "coverage"));
    }

    private static Map<String, Object> gitFileContent() {
        return schema(Map.ofEntries(Map.entry("repositoryId", repositoryId()), Map.entry("snapshotId", gitEvidenceId()),
                Map.entry("revision", revision()), Map.entry("path", string()), Map.entry("pathKey", string()),
                Map.entry("contentStatus", string()), Map.entry("content", string()), Map.entry("startLine", nonNegativeInteger()),
                Map.entry("endLine", nonNegativeInteger()), Map.entry("startLineComplete", Map.of("type", "boolean")),
                Map.entry("endLineComplete", Map.of("type", "boolean")), Map.entry("nextCursor", string())),
                List.of("repositoryId", "snapshotId", "revision", "path", "pathKey", "contentStatus", "content", "startLine", "endLine", "startLineComplete", "endLineComplete"));
    }

    private static Map<String, Object> gitTextSearchResult() {
        return schema(Map.of("repositoryId", repositoryId(), "snapshotId", gitEvidenceId(), "revision", revision(), "items", items(gitTextMatch()),
                "scanComplete", Map.of("type", "boolean"), "nextCursor", string(), "coverage", gitSnapshotCoverage()),
                List.of("repositoryId", "snapshotId", "revision", "items", "scanComplete", "coverage"));
    }

    private static Map<String, Object> gitFileItem() {
        return schema(Map.of("path", string(), "pathKey", string(), "entryType", Map.of("type", "string", "enum", List.of("FILE", "DIRECTORY")), "byteLength", nonNegativeInteger(), "contentStatus", string()),
                List.of("path", "pathKey", "entryType", "byteLength", "contentStatus"));
    }

    private static Map<String, Object> gitTextMatch() {
        return schema(Map.of("path", string(), "pathKey", string(), "line", positiveInteger(), "column", positiveInteger(), "snippet", string(),
                "snippetTruncated", Map.of("type", "boolean")), List.of("path", "pathKey", "line", "column", "snippet", "snippetTruncated"));
    }

    private static Map<String, Object> gitSnapshotCoverage() {
        return schema(Map.of("inventoryCount", nonNegativeInteger(), "readableTextCount", nonNegativeInteger(), "binaryCount", nonNegativeInteger(),
                "unsupportedEncodingCount", nonNegativeInteger(), "tooLargeCount", nonNegativeInteger(), "symlinkCount", nonNegativeInteger(),
                "submoduleCount", nonNegativeInteger(), "lfsPointerCount", nonNegativeInteger(), "unsupportedPathCount", nonNegativeInteger()),
                List.of("inventoryCount", "readableTextCount", "binaryCount", "unsupportedEncodingCount", "tooLargeCount", "symlinkCount", "submoduleCount", "lfsPointerCount", "unsupportedPathCount"));
    }

    private static Map<String, Object> gitChange() {
        return schema(Map.of("changeId", string(), "kind", string(), "oldPath", string(), "newPath", string(), "oldMode", string(),
                "newMode", string(), "oldBlobId", string(), "newBlobId", string(), "diffStatus", string()), List.of("changeId", "kind", "diffStatus"));
    }

    private static Map<String, Object> repositoryItem() {
        return schema(Map.of("repositoryId", string(), "revision", string()), List.of("repositoryId", "revision"));
    }

    private static Map<String, Object> searchCodeResult() {
        return schema(Map.of("repositoryId", string(), "revision", string(), "items", items(internalProgramElement()), "page", page(),
                "sourceCoverage", sourceCoverage()), List.of("repositoryId", "revision", "items", "page", "sourceCoverage"));
    }

    private static Map<String, Object> factSourceResult() {
        return schema(Map.of("repositoryId", string(), "revision", string(), "factId", string(), "source", sourceSnippet(),
                "factRange", factRange()), List.of("repositoryId", "revision", "factId", "source", "factRange"));
    }

    private static Map<String, Object> collection(Map<String, Object> item) {
        return schema(Map.of("repositoryId", string(), "revision", string(), "items", items(item), "page", page()),
                List.of("repositoryId", "revision", "items", "page"));
    }

    private static Map<String, Object> page() {
        return schema(Map.of("offset", nonNegativeInteger(), "limit", positiveInteger(), "returned", nonNegativeInteger(),
                "total", nonNegativeInteger(), "hasMore", Map.of("type", "boolean")),
                List.of("offset", "limit", "returned", "total", "hasMore"));
    }

    private static Map<String, Object> sourceCoverage() {
        return schema(Map.of("indexedSourceCount", nonNegativeInteger(), "issueCount", nonNegativeInteger(),
                "issueCodes", items(string())), List.of("indexedSourceCount", "issueCount", "issueCodes"));
    }

    private static Map<String, Object> reviewResult(Map<String, Object> result) {
        return schema(Map.of("context", reviewContext(), "result", result), List.of("context", "result"));
    }

    private static Map<String, Object> reviewContext() {
        return schema(Map.of("repositoryId", repositoryId(), "reviewId", reviewId(), "side", enumValue(ReviewSide.values()),
                "revision", revision(), "generationId", string(), "coverage", reviewCoverage()),
                List.of("repositoryId", "reviewId", "side", "revision", "generationId", "coverage"));
    }

    private static Map<String, Object> reviewCoverage() {
        return schema(Map.of("sourceCoverage", sourceCoverage(), "semanticLimitations", items(string())),
                List.of("sourceCoverage", "semanticLimitations"));
    }

    private static Map<String, Object> reviewDetails() {
        Map<String, Object> endpoint = schema(Map.of("revision", revision(), "generationId", string(), "manifestDigest", string(),
                "analysisFingerprint", string(), "snapshotId", string(), "coverage", reviewCoverage()),
                List.of("revision", "generationId", "manifestDigest", "analysisFingerprint", "snapshotId", "coverage"));
        Map<String, Object> commit = schema(Map.of("kind", Map.of("type", "string", "const", "COMMIT"),
                "revision", revision()), List.of("kind", "revision"));
        Map<String, Object> range = schema(Map.of("kind", Map.of("type", "string", "const", "RANGE"),
                "beforeRevision", revision(), "afterRevision", revision()), List.of("kind", "beforeRevision", "afterRevision"));
        Map<String, Object> selection = Map.of("oneOf", List.of(commit, range));
        Map<String, Object> resolved = schema(Map.of("beforeRevision", revision(), "afterRevision", revision(),
                "baselineRule", Map.of("type", "string", "enum", List.of("FIRST_PARENT", "EMPTY_TREE", "DIRECT_RANGE"))),
                List.of("afterRevision", "baselineRule"));
        Map<String, Object> before = schema(Map.of("kind", Map.of("type", "string",
                "enum", List.of("FIRST_PARENT", "EMPTY_TREE", "DIRECT_RANGE")), "endpoint", endpoint), List.of("kind"));
        return schema(Map.of("repositoryId", repositoryId(), "reviewId", reviewId(), "selection", selection,
                "resolvedEndpoints", resolved, "before", before, "after", endpoint,
                "comparisonId", string(), "publishedAt", string()),
                List.of("repositoryId", "reviewId", "selection", "resolvedEndpoints", "before", "after", "comparisonId", "publishedAt"));
    }

    private static Map<String, Object> sourceSnippet() {
        return schema(Map.of("path", string(), "startLine", positiveInteger(), "endLine", positiveInteger(), "code", string()),
                List.of("path", "startLine", "endLine", "code"));
    }

    private static Map<String, Object> factRange() {
        return schema(Map.of("startLine", positiveInteger(), "endLine", positiveInteger()), List.of("startLine", "endLine"));
    }

    private static Map<String, Object> internalProgramElement() {
        return schema(Map.of("factId", string(), "kind", enumValue(CodeFactKind.values()), "displayName", string(),
                "source", sourceSnippet()), List.of("factId", "kind", "displayName", "source"));
    }

    private static Map<String, Object> externalCallee() {
        return schema(Map.of("displayName", string()), List.of("displayName"));
    }

    private static Map<String, Object> entryPointItem() {
        return schema(Map.of("factId", string(), "handler", internalProgramElement(), "trigger", trigger()),
                List.of("factId", "handler", "trigger"));
    }

    private static Map<String, Object> trigger() {
        return schema(Map.of("kind", string(), "method", string(), "value", string()), List.of("kind"));
    }

    private static Map<String, Object> eventListenerItem() {
        return schema(Map.of("eventType", string(), "handler", internalProgramElement()), List.of("eventType", "handler"));
    }

    private static Map<String, Object> implementationItem() {
        return schema(Map.of("implementation", internalProgramElement(), "relationKind", Map.of("type", "string", "enum",
                List.of("IMPLEMENTS", "OVERRIDES"))), List.of("implementation", "relationKind"));
    }

    private static Map<String, Object> referenceItem() {
        return schema(Map.of("container", internalProgramElement(), "referenceSite", relationSite()), List.of("container", "referenceSite"));
    }

    private static Map<String, Object> callerItem() {
        return schema(Map.of("caller", internalProgramElement(), "callSite", relationSite()), List.of("caller", "callSite"));
    }

    private static Map<String, Object> calleeItem() {
        return schema(Map.of("callee", Map.of("oneOf", List.of(internalProgramElement(), externalCallee())), "callSite", relationSite(),
                        "resolutionStatus", enumValue(SemanticQueryContract.CalleeResolutionStatus.values())),
                List.of("callee", "callSite", "resolutionStatus"));
    }

    private static Map<String, Object> relationSite() {
        return schema(Map.of("factId", string(), "source", sourceSnippet()), List.of("factId", "source"));
    }

    private static Map<String, Object> items(Map<String, Object> item) {
        return Map.of("type", "array", "items", item);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> nonNegativeInteger() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private static Map<String, Object> positiveInteger() {
        return Map.of("type", "integer", "minimum", 1);
    }

    private static Map<String, Object> enumValue(Enum<?>[] values) {
        return Map.of("type", "string", "enum", enumNames(values));
    }

    private static Map<String, Map<String, Object>> inputSchemas() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("list_git_branches", pagedSchema(Map.of("repositoryId", repositoryId(), "catalogId", gitEvidenceId()),
                List.of("repositoryId")));
        schemas.put("list_git_commits", pagedSchema(Map.of("repositoryId", repositoryId(), "historyId", gitEvidenceId(), "revision", revision()),
                List.of("repositoryId", "historyId", "revision")));
        schemas.put("compare_revisions", pagedSchema(Map.of("repositoryId", repositoryId(), "comparisonId", gitEvidenceId(), "previous", revision(), "current", revision()),
                List.of("repositoryId", "comparisonId", "previous", "current")));
        schemas.put("get_file_diff", schema(Map.of("repositoryId", repositoryId(), "comparisonId", gitEvidenceId(), "previous", revision(),
                "current", revision(), "changeId", string(), "cursor", string()), List.of("repositoryId", "comparisonId", "previous", "current", "changeId")));
        schemas.put("list_files", pagedSchema(Map.of("repositoryId", repositoryId(), "snapshotId", gitEvidenceId(), "revision", revision(),
                "directory", string()), List.of("repositoryId", "snapshotId", "revision", "directory")));
        schemas.put("read_file", schema(Map.of("repositoryId", repositoryId(), "snapshotId", gitEvidenceId(), "revision", revision(), "path", Map.of("type", "string", "minLength", 1),
                "startLine", positiveInteger(), "maxLines", Map.of("type", "integer", "minimum", 1, "maximum", SemanticQueryContract.MAX_FILE_LINES,
                        "default", SemanticQueryContract.DEFAULT_FILE_LINES), "cursor", Map.of("type", "string", "minLength", 1)), List.of("repositoryId", "snapshotId", "revision", "path")));
        schemas.put("search_text", schema(Map.of("repositoryId", repositoryId(), "snapshotId", gitEvidenceId(), "revision", revision(),
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 256), "directory", string(), "cursor", Map.of("type", "string", "minLength", 1), "limit", Map.of("type", "integer", "minimum", 1,
                        "maximum", SemanticQueryContract.MAX_LIMIT, "default", SemanticQueryContract.DEFAULT_LIMIT)),
                List.of("repositoryId", "snapshotId", "revision", "query")));
        schemas.put("list_repositories", pagedSchema(Map.of(), List.of()));
        schemas.put("get_repository", schema(Map.of("repositoryId", repositoryId()), List.of("repositoryId")));
        schemas.put("search_code", pagedSchema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "query", query(), "kinds", codeFactKinds(),
                "packagePrefix", Map.of("type", "string", "description", "Optional fully qualified prefix used only to narrow search.")),
                List.of("repositoryId", "revision", "query")));
        schemas.put("get_fact_source", schema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "factId", factId("factId"), "contextLines", contextLines()),
                List.of("repositoryId", "revision", "factId")));
        schemas.put("list_entry_points", pagedSchema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "kinds", entryPointKinds()),
                List.of("repositoryId", "revision")));
        schemas.put("find_api_routes", pagedSchema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "httpMethod", httpMethod(), "path", path()),
                List.of("repositoryId", "revision", "httpMethod", "path")));
        schemas.put("get_review", schema(Map.of("repositoryId", repositoryId(), "reviewId", reviewId()),
                List.of("repositoryId", "reviewId")));
        schemas.put("review_search_code", reviewPagedSchema(Map.of("query", query(), "kinds", codeFactKinds(),
                "packagePrefix", Map.of("type", "string", "description", "Optional fully qualified prefix used only to narrow search.")),
                List.of("query")));
        schemas.put("review_get_fact_source", reviewSchema(Map.of("factId", factId("factId"), "contextLines", contextLines()),
                List.of("factId")));
        schemas.put("review_list_entry_points", reviewPagedSchema(Map.of("kinds", entryPointKinds()), List.of()));
        schemas.put("review_find_api_routes", reviewPagedSchema(Map.of("httpMethod", httpMethod(), "path", path()),
                List.of("httpMethod", "path")));
        schemas.put("review_find_event_listeners", reviewPagedSchema(Map.of("eventType", eventType()), List.of("eventType")));
        schemas.put("review_list_type_members", reviewPagedSchema(Map.of("typeFactId", factId("typeFactId"), "kinds", memberKinds()),
                List.of("typeFactId")));
        schemas.put("review_find_method_implementations", reviewRelationSchema("methodFactId"));
        schemas.put("review_find_references", reviewRelationSchema("factId"));
        schemas.put("review_find_callers", reviewRelationSchema("methodFactId"));
        schemas.put("review_find_callees", reviewRelationSchema("methodFactId"));
        schemas.put("find_event_listeners", pagedSchema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "eventType", eventType()),
                List.of("repositoryId", "revision", "eventType")));
        schemas.put("list_type_members", pagedSchema(Map.of(
                "repositoryId", repositoryId(), "revision", revision(), "typeFactId", factId("typeFactId"), "kinds", memberKinds()),
                List.of("repositoryId", "revision", "typeFactId")));
        schemas.put("find_method_implementations", relationSchema("methodFactId"));

        schemas.put("find_references", relationSchema("factId"));
        schemas.put("find_callers", relationSchema("methodFactId"));
        schemas.put("find_callees", relationSchema("methodFactId"));
        return Map.copyOf(schemas);
    }

    private static Map<String, Object> relationSchema(String fieldName) {
        return pagedSchema(Map.of("repositoryId", repositoryId(), "revision", revision(), fieldName, factId(fieldName)),
                List.of("repositoryId", "revision", fieldName));
    }
    private static Map<String, Object> reviewRelationSchema(String fieldName) {
        return reviewPagedSchema(Map.of(fieldName, factId(fieldName)), List.of(fieldName));
    }

    private static Map<String, Object> reviewPagedSchema(Map<String, Object> fields, List<String> required) {
        Map<String, Object> properties = reviewProperties(fields);
        properties.put("offset", Map.of("type", "integer", "minimum", 0, "default", 0));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", SemanticQueryContract.MAX_LIMIT,
                "default", SemanticQueryContract.DEFAULT_LIMIT));
        return schema(properties, reviewRequired(required));
    }

    private static Map<String, Object> reviewSchema(Map<String, Object> fields, List<String> required) {
        return schema(reviewProperties(fields), reviewRequired(required));
    }

    private static Map<String, Object> reviewProperties(Map<String, Object> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repositoryId", repositoryId());
        properties.put("reviewId", reviewId());
        properties.put("side", enumValue(ReviewSide.values()));
        properties.put("revision", revision());
        properties.putAll(fields);
        return properties;
    }

    private static List<String> reviewRequired(List<String> fields) {
        List<String> required = new ArrayList<>(List.of("repositoryId", "reviewId", "side", "revision"));
        required.addAll(fields);
        return List.copyOf(required);
    }

    private static Map<String, Object> pagedSchema(Map<String, Object> fields, List<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>(fields);
        properties.put("offset", Map.of("type", "integer", "minimum", 0, "default", 0));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", SemanticQueryContract.MAX_LIMIT,
                "default", SemanticQueryContract.DEFAULT_LIMIT));
        return schema(properties, required);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", Map.copyOf(properties), "required", List.copyOf(required),
                "additionalProperties", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static Map<String, Object> repositoryId() {
        return Map.of("type", "string", "minLength", RepositoryId.MIN_LENGTH, "maxLength", RepositoryId.MAX_LENGTH,
                "pattern", RepositoryId.PATTERN, "description", "Copy repositoryId exactly from a Semantic result.");
    }

    private static Map<String, Object> reviewId() {
        return Map.of("type", "string", "minLength", 1, "description", "Copy reviewId exactly from a Semantic review result.");
    }

    private static Map<String, Object> revision() {
        return Map.of("type", "string", "minLength", RepositoryRevision.LENGTH, "maxLength", RepositoryRevision.LENGTH,
                "pattern", RepositoryRevision.PATTERN, "description", "Copy revision exactly from a Semantic result.");
    }

    private static Map<String, Object> factId(String fieldName) {
        return Map.of("type", "string", "minLength", CodeFactId.LENGTH, "maxLength", CodeFactId.LENGTH, "pattern", CodeFactId.PATTERN,
                "description", "Copy " + fieldName + " exactly from a Semantic result.");
    }

    private static Map<String, Object> gitEvidenceId() {
        return Map.of("type", "string", "format", "uuid", "description", "Copy the immutable Git evidence ID exactly from a Git result.");
    }

    private static Map<String, Object> query() {
        return Map.of("type", "string", "minLength", CodeFactSearchQuery.MIN_QUERY_LENGTH,
                "maxLength", CodeFactSearchQuery.MAX_QUERY_LENGTH);
    }

    private static Map<String, Object> codeFactKinds() {
        return enumArray(CodeFactKind.values());
    }

    private static Map<String, Object> memberKinds() {
        return enumArray(TypeMemberQuery.MEMBER_KINDS.toArray(CodeFactKind[]::new));
    }

    private static Map<String, Object> entryPointKinds() {
        return enumArray(EntryPointKind.values());
    }

    private static Map<String, Object> httpMethod() {
        return Map.of("type", "string", "enum", enumNames(SemanticQueryContract.HttpMethod.values()));
    }

    private static Map<String, Object> contextLines() {
        return Map.of("type", "integer", "minimum", 0, "maximum", SemanticQueryContract.MAX_CONTEXT_LINES, "default", 0);
    }

    private static Map<String, Object> path() {
        return Map.of("type", "string", "minLength", 1, "description", "Exact indexed route path.");
    }

    private static Map<String, Object> eventType() {
        return Map.of("type", "string", "minLength", 1,
                "description", "Exact fully qualified event type; do not guess an external type.");
    }

    private static Map<String, Object> enumArray(Enum<?>[] values) {
        return Map.of("type", "array", "items", Map.of("type", "string", "enum", enumNames(values)));
    }

    private static List<String> enumNames(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return List.copyOf(names);
    }
}
