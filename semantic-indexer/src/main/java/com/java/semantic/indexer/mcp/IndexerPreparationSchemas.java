package com.java.semantic.indexer.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.model.review.ReviewBaselineRule;
import java.util.Arrays;

/** Explicit schemas for application success and error, without domain-value wrappers. */
public final class IndexerPreparationSchemas {
    private IndexerPreparationSchemas() { }
    private static Map<String, Object> text() { return Map.of("type", "string", "minLength", 1, "pattern", "\\S"); }
    private static Map<String, Object> repositoryId() { return Map.of("type", "string", "pattern", RepositoryId.PATTERN); }
    private static Map<String, Object> enumeration(Enum<?>[] values) {
        return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList());
    }
    private static Map<String, Object> uuid() { return Map.of("type", "string", "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"); }
    private static Map<String, Object> sha() { return Map.of("type", "string", "pattern", "^[0-9a-f]{40}$"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer", "minimum", 0); }
    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
    }
    public static Map<String, Object> selection() {
        return Map.of("oneOf", List.of(
                object(Map.of("kind", Map.of("const", "COMMIT"), "revision", sha()), "kind", "revision"),
                object(Map.of("kind", Map.of("const", "RANGE"), "beforeRevision", sha(), "afterRevision", sha()),
                        "kind", "beforeRevision", "afterRevision")));
    }
    public static Map<String, Object> input(String operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repositoryId", repositoryId());
        fields.put("requestId", uuid());
        return switch (operation) {
            case "refresh_repository_metadata" -> {
                fields.put("branch", text());
                yield object(fields, "repositoryId", "requestId");
            }
            case "prepare_review" -> {
                fields.put("selection", selection());
                yield object(fields, "repositoryId", "requestId", "selection");
            }
            case "prepare_codebase" -> object(fields, "repositoryId", "requestId");
            case "get_job" -> {
                fields.put("jobId", text());
                Map<String, Object> schema = new LinkedHashMap<>(object(fields, "repositoryId"));
                schema.put("oneOf", List.of(Map.of("required", List.of("jobId"), "not", Map.of("required", List.of("requestId"))),
                        Map.of("required", List.of("requestId"), "not", Map.of("required", List.of("jobId")))));
                yield Map.copyOf(schema);
            }
            default -> throw new IllegalArgumentException("unknown preparation operation");
        };
    }
    public static Map<String, Object> success() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jobId", text()); fields.put("repositoryId", repositoryId()); fields.put("requestId", uuid());
        fields.put("operation", enumeration(IndexJobOperation.values()));
        fields.put("phase", enumeration(IndexJobPhase.values())); fields.put("active", Map.of("type", "boolean"));
        fields.put("failureCategory", enumeration(IndexFailureCategory.values()));
        fields.put("preparationBranch", text()); fields.put("branch", text());
        fields.put("headRevision", sha());
        fields.put("requested", Map.of("oneOf", List.of(
                object(Map.of("operation", Map.of("const", "PREPARE_CODEBASE")), "operation"),
                object(Map.of("operation", Map.of("const", "REFRESH_REPOSITORY_METADATA"), "branch", text()), "operation"),
                object(Map.of("operation", Map.of("const", "PREPARE_REVIEW"), "selection", selection()), "operation", "selection"))));
        fields.put("target", object(Map.of("revision", sha(), "generationId", text(), "generation", integer()), "revision", "generationId", "generation"));
        fields.put("metadataResult", object(Map.of("historyId", uuid(), "catalogId", uuid(), "repositoryId", repositoryId(),
                "branch", text(), "headRevision", sha(), "observedAt", text(),
                "coverage", object(Map.of("total", integer()), "total")), "historyId", "catalogId", "repositoryId", "branch", "headRevision", "observedAt", "coverage"));
        fields.put("currentPointer", object(Map.of("revision", sha(), "generationId", text(), "manifestDigest", text(),
                "committedJobId", text(), "publishedAt", text()), "revision", "generationId", "manifestDigest", "committedJobId", "publishedAt"));
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("reviewId", uuid()); review.put("selection", selection()); review.put("stage", enumeration(ReviewPreparationStage.values()));
        review.put("resolvedEndpoints", object(Map.of("beforeRevision", sha(), "afterRevision", sha(),
                "baselineRule", enumeration(ReviewBaselineRule.values())), "afterRevision", "baselineRule"));
        for (String field : List.of("beforeGenerationId", "afterGenerationId", "comparisonId", "previousSnapshotId", "currentSnapshotId")) {
            review.put(field, text());
        }
        fields.put("review", object(review, "reviewId", "selection", "stage"));
        return object(fields, "jobId", "repositoryId", "operation", "phase", "active");
    }
    public static Map<String, Object> error() {
        return object(Map.of("code", text(), "message", text(), "retryable", Map.of("type", "boolean"),
                "jobId", text(), "requestId", uuid()), "code", "message", "retryable");
    }
    public static Map<String, Object> output() {
        return Map.of("type", "object", "oneOf", List.of(success(), error()));
    }
}
