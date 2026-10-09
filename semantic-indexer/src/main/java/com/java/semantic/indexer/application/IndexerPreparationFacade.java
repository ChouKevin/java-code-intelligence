package com.java.semantic.indexer.application;

import com.java.semantic.indexer.index.SemanticIndexJob;
import com.java.semantic.indexer.job.PreparationJob;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** HTTP and MCP consume the same strict input and durable result. */
@Service
public final class IndexerPreparationFacade {
    private final PreparationCoordinator service;

    public IndexerPreparationFacade(PreparationCoordinator service) {
        this.service = service;
    }

    public Map<String, Object> prepareSource(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("prepare_source", fields);
        return result(service.prepareSource(input.repositoryId(), input.requestId().orElseThrow(), input.revision()));
    }

    public Map<String, Object> prepareSemanticIndex(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("prepare_semantic_index", fields);
        return result(service.prepareSemanticIndex(input.repositoryId(), input.requestId().orElseThrow(), input.revision().orElseThrow()));
    }

    public Map<String, Object> getJob(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("get_job", fields);
        return result(service.getJob(input.repositoryId(), input.jobId(), input.requestId()));
    }

    public static Map<String, Object> result(PreparationJob prepared) {
        if (prepared instanceof SemanticIndexJob index) return indexResult(index);
        if (!(prepared instanceof SourcePreparationJob job)) throw new IllegalArgumentException("unsupported preparation job");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("formatVersion", SourceRevisionManifest.FORMAT_VERSION);
        values.put("operation", "prepare_source");
        values.put("jobId", job.jobId());
        values.put("repositoryId", job.repositoryId());
        values.put("requestId", job.requestId());
        values.put("defaultBranch", job.defaultBranch());
        values.put("phase", job.phase().name());
        values.put("acceptedAt", job.acceptedAt().toString());
        job.requestedRevision().ifPresent(value -> values.put("requestedRevision", value));
        job.resolvedRevision().ifPresent(value -> values.put("resolvedRevision", value));
        job.expectedCurrent().ifPresent(value -> values.put("expectedCurrent", value));
        job.publication().ifPresent(value -> values.put("publication", value));
        job.failureCode().ifPresent(value -> values.put("failureCode", value));
        return Map.copyOf(values);
    }

    private static Map<String, Object> indexResult(SemanticIndexJob job) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("formatVersion", 1);
        values.put("operation", "prepare_semantic_index");
        values.put("jobId", job.jobId());
        values.put("repositoryId", job.repositoryId());
        values.put("requestId", job.requestId());
        values.put("revision", job.revision());
        values.put("indexGeneration", job.indexGeneration());
        values.put("phase", job.phase().name());
        values.put("acceptedAt", job.acceptedAt().toString());
        job.publication().ifPresent(value -> values.put("publication", value));
        job.failureCode().ifPresent(value -> values.put("failureCode", value));
        return Map.copyOf(values);
    }
}
