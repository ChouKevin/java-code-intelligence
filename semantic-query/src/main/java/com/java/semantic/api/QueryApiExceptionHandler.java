package com.java.semantic.api;

import com.java.semantic.query.application.RevisionOutdatedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable query failure envelopes; callers can retry a pointer switch with the current revision. */
@RestControllerAdvice
public final class QueryApiExceptionHandler {

    @ExceptionHandler(RevisionOutdatedException.class)
    public ResponseEntity<RevisionOutdatedResponse> revisionOutdated(RevisionOutdatedException exception) {
        RevisionOutdatedResponse response = new RevisionOutdatedResponse("REVISION_OUTDATED", exception.repositoryId().value(),
                exception.requestedRevision().value(), exception.currentRevision().value(), "Retry with currentRevision.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    public record RevisionOutdatedResponse(String code, String repositoryId, String requestedRevision, String currentRevision,
                                           String retryGuidance) {
    }
}
