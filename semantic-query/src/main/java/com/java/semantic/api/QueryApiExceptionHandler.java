package com.java.semantic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.query.application.CodeFactKindUnsupportedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.IndexContractMismatchException;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.InvalidCodeFactQueryException;
import com.java.semantic.query.application.RepositoryNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticIndexUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import jakarta.validation.ConstraintViolationException;

import java.util.Map;

/** Stable query failure envelopes; callers can retry a pointer switch with the current revision. */
@RestControllerAdvice
public final class QueryApiExceptionHandler {

    @ExceptionHandler(RevisionOutdatedException.class)
    public ResponseEntity<RevisionOutdatedResponse> revisionOutdated(RevisionOutdatedException exception) {
        RevisionOutdatedResponse response = new RevisionOutdatedResponse("REVISION_OUTDATED", exception.repositoryId().value(),
                exception.requestedRevision().value(), exception.currentRevision().value(), "Retry with currentRevision.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler({RepositoryNotFoundException.class, CodeFactNotFoundException.class, IndexNotReadyException.class,
            IndexContractMismatchException.class, SemanticIndexUnavailableException.class, CodeFactKindUnsupportedException.class,
            InvalidCodeFactQueryException.class, IllegalArgumentException.class})
    public ResponseEntity<QueryFailureResponse> failure(RuntimeException exception) {
        FailureDefinition definition = failureDefinition(exception);
        return ResponseEntity.status(definition.status()).body(new QueryFailureResponse(definition.code(), definition.retryable(), null)); // cs-allow
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, BindException.class,
            MissingServletRequestParameterException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<QueryFailureResponse> requestFailure(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(requestInvalidResponse());
    }

    /** Stable, sanitized MCP error body for the same authoritative Query failures. */
    public static Map<String, Object> failureBody(RuntimeException exception) {
        FailureDefinition definition = failureDefinition(exception);
        return Map.of("code", definition.code(), "retryable", definition.retryable());
    }

    public static QueryFailureResponse requestInvalidResponse() {
        return new QueryFailureResponse("REQUEST_INVALID", false, null); // cs-allow
    }

    private static FailureDefinition failureDefinition(RuntimeException exception) {
        if (exception instanceof RepositoryNotFoundException) {
            return new FailureDefinition(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", false);
        }
        if (exception instanceof CodeFactNotFoundException) {
            return new FailureDefinition(HttpStatus.NOT_FOUND, "CODE_FACT_NOT_FOUND", false);
        }
        if (exception instanceof IndexNotReadyException) {
            return new FailureDefinition(HttpStatus.SERVICE_UNAVAILABLE, "INDEX_NOT_READY", true);
        }
        if (exception instanceof IndexContractMismatchException) {
            return new FailureDefinition(HttpStatus.SERVICE_UNAVAILABLE, "INDEX_CONTRACT_MISMATCH", true);
        }
        if (exception instanceof SemanticIndexUnavailableException) {
            return new FailureDefinition(HttpStatus.SERVICE_UNAVAILABLE, "SEMANTIC_INDEX_UNAVAILABLE", true);
        }
        if (exception instanceof CodeFactKindUnsupportedException) {
            return new FailureDefinition(HttpStatus.BAD_REQUEST, "CODE_FACT_KIND_UNSUPPORTED", false);
        }
        if (exception instanceof InvalidCodeFactQueryException || exception instanceof IllegalArgumentException) {
            return new FailureDefinition(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false);
        }
        return new FailureDefinition(HttpStatus.INTERNAL_SERVER_ERROR, "QUERY_FAILURE", false);
    }

    public record RevisionOutdatedResponse(String code, String repositoryId, String requestedRevision, String currentRevision,
                                           String retryGuidance) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryFailureResponse(String code, boolean retryable, String message) {
    }

    private record FailureDefinition(HttpStatus status, String code, boolean retryable) {
    }
}
