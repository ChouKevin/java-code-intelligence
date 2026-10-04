package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.source.RepositoryRegistry.RepositoryNotConfiguredException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed safe failures: never serialize raw Git, filesystem, URL, or credentials. */
public final class IndexerPreparationErrorMapper {
    public record Failure(int status, Map<String, Object> body) { }

    public Failure map(RuntimeException exception) {
        if (exception instanceof PreparationRequestReusedException reused) {
            return failure(409, "REQUEST_ID_REUSED", "Look up the original requestId; do not resubmit.", false,
                    Map.of("jobId", reused.jobId().value(), "requestId", reused.requestId().value()));
        }
        if (exception instanceof PreparationRequestNotFoundException) {
            return failure(404, "REQUEST_NOT_FOUND", "Acceptance is unknown; look up the original requestId only.", true, Map.of());
        }
        if (exception instanceof IndexJobNotFoundException) {
            return failure(404, "JOB_NOT_FOUND", "The job was not found in this repository.", false, Map.of());
        }
        if (exception instanceof RepositoryNotConfiguredException) {
            return failure(404, "REPOSITORY_NOT_FOUND", "The configured repository was not found.", false, Map.of());
        }
        if (exception instanceof IndexJobAlreadyActiveException) {
            return failure(409, "REPOSITORY_ACTIVE", "The repository has active work; no new work was accepted.", false, Map.of());
        }
        if (exception instanceof IllegalArgumentException) {
            return failure(400, "INVALID_ARGUMENT", "The request does not satisfy the preparation contract.", false, Map.of());
        }
        return failure(503, "SOURCE_UNAVAILABLE", "Source preparation is unavailable; if acceptance is unknown, look up the original requestId.",
                true, Map.of());
    }

    private static Failure failure(int status, String code, String message, boolean retryable,
            Map<String, Object> identities) {
        Map<String, Object> body = new LinkedHashMap<>(identities);
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", retryable);
        return new Failure(status, Map.copyOf(body));
    }
}
