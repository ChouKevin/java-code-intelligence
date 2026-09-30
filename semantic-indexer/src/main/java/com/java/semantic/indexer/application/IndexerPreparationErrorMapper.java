package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.store.SemanticIndexUnavailableException;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.mongodb.MongoException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe application failures; never serializes exception messages or local paths. */
public final class IndexerPreparationErrorMapper {
    public record Failure(int status, Map<String, Object> body) { }
    public Failure map(RuntimeException exception) {
        if (exception instanceof PreparationRequestReusedException reused) {
            return failure(409, "REQUEST_ID_REUSED", "This requestId already belongs to a job. Look up that job; do not resubmit.", false,
                    Map.of("jobId", reused.jobId().value(), "requestId", reused.requestId().value()));
        }
        if (exception instanceof PreparationRequestNotFoundException) {
            return failure(404, "REQUEST_NOT_FOUND", "Acceptance is unknown: no persisted job is currently visible. Continue looking up the original requestId or stop waiting; do not resubmit.", true, Map.of());
        }
        if (exception instanceof IndexJobNotFoundException) {
            return failure(404, "JOB_NOT_FOUND", "The job was not found in this repository.", false, Map.of());
        }
        if (exception instanceof RepositoryNotFoundException) {
            return failure(404, "REPOSITORY_NOT_FOUND", "The configured repository was not found.", false, Map.of());
        }
        if (exception instanceof IndexJobAlreadyActiveException || exception instanceof RepositoryBusyException) {
            return failure(409, "REPOSITORY_ACTIVE", "The repository has active work. Look up the accepted job; no new work was accepted.", false, Map.of());
        }
        if (exception instanceof SemanticIndexUnavailableException || exception instanceof org.springframework.dao.DataAccessException
                || exception instanceof MongoException) {
            return failure(503, "INDEX_UNAVAILABLE", "Preparation storage is unavailable. If submission acceptance is unknown, look up the original requestId; do not resubmit.", true, Map.of());
        }
        if (exception instanceof RepositoryMutationException) {
            return failure(503, "REPOSITORY_UNAVAILABLE", "The configured repository target could not be resolved. No preparation was accepted.", false, Map.of());
        }
        if (exception instanceof IllegalArgumentException) {
            return failure(400, "INVALID_ARGUMENT", "The request does not satisfy the preparation contract.", false, Map.of());
        }
        throw exception;
    }
    private static Failure failure(int status, String code, String message, boolean retryable, Map<String, Object> identities) {
        Map<String, Object> body = new LinkedHashMap<>(identities);
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", retryable);
        return new Failure(status, Map.copyOf(body));
    }
}
