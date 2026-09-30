package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.PreparationRequest;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewSelection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** One admission and exact recovery surface; adapters bind only transport fields. */
@Service
public final class IndexerPreparationFacade {
    private final IndexRequestService requests;
    public IndexerPreparationFacade(IndexRequestService requests) {
        this.requests = Objects.requireNonNull(requests, "requests are required");
    }
    public Map<String, Object> refreshRepositoryMetadata(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("refresh_repository_metadata", fields);
        return result(requests.refreshRepositoryMetadata(input.repositoryId(), input.requestId().orElseThrow(), input.branch()));
    }
    public Map<String, Object> prepareCodebase(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("prepare_codebase", fields);
        return result(requests.prepareCodebase(input.repositoryId(), input.requestId().orElseThrow()));
    }
    public Map<String, Object> prepareReview(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("prepare_review", fields);
        return result(requests.prepareReview(input.repositoryId(), input.requestId().orElseThrow(), input.selection().orElseThrow()));
    }
    public Map<String, Object> getJob(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("get_job", fields);
        Map<String, Object> result = new LinkedHashMap<>(result(requests.getJob(input.repositoryId(), input.jobId(), input.requestId())));
        requests.currentPointer(input.repositoryId()).ifPresent(pointer -> result.put("currentPointer", Map.of(
                "revision", pointer.revision().value(), "generationId", pointer.generationId().value(),
                "manifestDigest", pointer.manifestDigest().value(), "committedJobId", pointer.committedJobId(),
                "publishedAt", pointer.publishedAt().toString())));
        return Map.copyOf(result);
    }
    public static Map<String, Object> result(IndexJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.id().value());
        result.put("repositoryId", job.repositoryId().value());
        result.put("operation", job.operation().name());
        result.put("phase", job.phase().name());
        result.put("active", job.active());
        job.failureCategory().ifPresent(failure -> result.put("failureCategory", failure.name()));
        job.preparation().ifPresent(request -> {
            result.put("requestId", request.requestId().value());
            result.put("requested", requested(request));
        });
        job.preparationBranch().ifPresent(branch -> result.put("preparationBranch", branch));
        job.target().ifPresent(target -> result.put("target", Map.of("revision", target.revision().value(),
                "generationId", target.generationId().value(), "generation", target.generation())));
        job.gitEvidence().ifPresent(metadata -> {
            metadata.branch().ifPresent(branch -> result.put("branch", branch));
            metadata.revision().ifPresent(revision -> result.put("headRevision", revision.value()));
            metadata.metadataResult().ifPresent(history -> result.put("metadataResult", Map.of(
                    "historyId", history.historyId().value(), "catalogId", history.catalogId().value(),
                    "repositoryId", history.repositoryId().value(), "branch", history.branch(),
                    "headRevision", history.revision().value(), "observedAt", history.preparedAt().toString(),
                    "coverage", Map.of("total", history.total()))));
        });
        job.review().ifPresent(review -> result.put("review", review(review)));
        return Map.copyOf(result);
    }
    private static Map<String, Object> requested(PreparationRequest request) {
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("operation", request.operation().name());
        request.branch().ifPresent(branch -> requested.put("branch", branch));
        request.selection().ifPresent(selected -> requested.put("selection", selection(selected)));
        return Map.copyOf(requested);
    }
    public static Map<String, String> selection(ReviewSelection selected) {
        return selected.kind() == ReviewComparisonType.COMMIT
                ? Map.of("kind", "COMMIT", "revision", selected.afterRevision().value())
                : Map.of("kind", "RANGE", "beforeRevision", selected.beforeRevision().orElseThrow().value(),
                        "afterRevision", selected.afterRevision().value());
    }
    private static Map<String, Object> review(ReviewJobPayload review) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewId", review.reviewId().value());
        result.put("selection", selection(review.selection()));
        result.put("stage", review.stage().name());
        review.resolvedEndpoints().ifPresent(endpoints -> {
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("afterRevision", endpoints.afterRevision().value());
            resolved.put("baselineRule", endpoints.baselineRule().name());
            endpoints.beforeRevision().ifPresent(revision -> resolved.put("beforeRevision", revision.value()));
            result.put("resolvedEndpoints", Map.copyOf(resolved));
        });
        review.before().ifPresent(generation -> result.put("beforeGenerationId", generation.selected().generationId().value()));
        review.after().ifPresent(generation -> result.put("afterGenerationId", generation.selected().generationId().value()));
        review.comparisonId().ifPresent(id -> result.put("comparisonId", id.value()));
        review.previousSnapshotId().ifPresent(id -> result.put("previousSnapshotId", id.value()));
        review.currentSnapshotId().ifPresent(id -> result.put("currentSnapshotId", id.value()));
        return Map.copyOf(result);
    }
}
