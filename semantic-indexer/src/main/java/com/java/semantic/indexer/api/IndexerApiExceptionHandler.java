package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable error envelope for Indexer administration failures with public machine-readable codes. */
@RestControllerAdvice
public final class IndexerApiExceptionHandler {
    @ExceptionHandler(IndexJobAlreadyActiveException.class)
    public ResponseEntity<ApiError> activeJob(IndexJobAlreadyActiveException exception) {
        ApiError error = new ApiError("REPOSITORY_ACTIVE", exception.getMessage(), exception.repositoryId().value(),
                List.of(), UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    public record ApiError(String errorCode, String message, String repoId, List<Map<String, Object>> candidates,
                           String requestId) {
    }
}
