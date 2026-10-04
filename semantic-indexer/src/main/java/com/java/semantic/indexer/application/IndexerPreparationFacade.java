package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.indexer.source.SourcePreparationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** HTTP and MCP consume the same strict input and durable result. */
@Service
public final class IndexerPreparationFacade {
    private final SourcePreparationService service;

    public IndexerPreparationFacade(SourcePreparationService service) {
        this.service = service;
    }

    public Map<String, Object> prepareSource(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("prepare_source", fields);
        return result(service.prepareSource(input.repositoryId(), input.requestId().orElseThrow(), input.revision()));
    }

    public Map<String, Object> getJob(Map<String, ?> fields) {
        IndexerPreparationContract.Input input = IndexerPreparationContract.input("get_job", fields);
        return result(service.getJob(input.repositoryId(), input.jobId(), input.requestId()));
    }

    public static Map<String, Object> result(SourcePreparationJob job) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("formatVersion", job.formatVersion());
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
}
