package com.java.semantic.indexer.mcp;

import com.java.semantic.model.repository.RepositoryId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-only MCP input and output contract; runtime binder owns strict validation. */
public final class IndexerPreparationSchemas {
    private IndexerPreparationSchemas() { }

    private static Map<String, Object> text() { return Map.of("type", "string", "minLength", 1); }
    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
    private static Map<String, Object> sha() { return Map.of("type", "string", "pattern", "^[0-9a-f]{40}$"); }
    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required),
                "additionalProperties", false);
    }

    public static Map<String, Object> input(String operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repositoryId", Map.of("type", "string", "pattern", RepositoryId.PATTERN));
        fields.put("requestId", uuid());
        if (operation.equals("prepare_source")) {
            fields.put("revision", sha());
            return object(fields, "repositoryId", "requestId");
        }
        if (operation.equals("get_job")) {
            fields.put("jobId", text());
            Map<String, Object> schema = new LinkedHashMap<>(object(fields, "repositoryId"));
            schema.put("oneOf", List.of(Map.of("required", List.of("jobId"),
                            "not", Map.of("required", List.of("requestId"))),
                    Map.of("required", List.of("requestId"), "not", Map.of("required", List.of("jobId")))));
            return schema;
        }
        throw new IllegalArgumentException("unknown source preparation operation");
    }

    public static Map<String, Object> output() {
        Map<String, Object> successFields = new LinkedHashMap<>();
        successFields.put("jobId", text());
        successFields.put("repositoryId", text());
        successFields.put("requestId", uuid());
        successFields.put("phase", text());
        successFields.put("acceptedAt", text());
        successFields.put("formatVersion", Map.of("type", "integer"));
        successFields.put("defaultBranch", text());
        successFields.put("resolvedRevision", sha());
        successFields.put("requestedRevision", sha());
        successFields.put("expectedCurrent", Map.of("type", "object"));
        successFields.put("publication", Map.of("type", "object"));
        successFields.put("failureCode", text());
        Map<String, Object> success = object(successFields, "jobId", "repositoryId", "requestId", "phase",
                "acceptedAt", "formatVersion", "defaultBranch");
        Map<String, Object> error = object(Map.of("code", text(), "message", text(),
                "retryable", Map.of("type", "boolean"), "jobId", text(), "requestId", uuid()),
                "code", "message", "retryable");
        return Map.of("type", "object", "oneOf", List.of(success, error));
    }
}
