package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.ImmutableFixtureException;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.InvalidRepositoryIdException;
import com.java.semantic.semantic.application.ImplementationTargetUnsupportedException;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Maps typed semantic failures to the fixed, client-safe error envelope. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidRepositoryIdException.class)
    public ResponseEntity<ApiErrorResponse> invalidRepositoryId(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "repository id is invalid", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "request body is invalid", request);
    }

    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> repositoryNotFound(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "repository is not configured", request);
    }

    @ExceptionHandler({RepositoryBusyException.class, RepositoryNotReadyException.class})
    public ResponseEntity<ApiErrorResponse> repositoryNotReady(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "repository is not ready", request);
    }

    @ExceptionHandler(ImmutableFixtureException.class)
    public ResponseEntity<ApiErrorResponse> immutableFixture(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "repository is not ready", request);
    }

    @ExceptionHandler(RepositoryRevisionMismatchException.class)
    public ResponseEntity<ApiErrorResponse> revisionMismatch(
            RepositoryRevisionMismatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.withContext(
                "REPOSITORY_REVISION_MISMATCH",
                "expected revision does not match current revision",
                null,
                exception.getExpectedRevision().value(),
                exception.getCurrentRevision().value(),
                null,
                List.of(),
                requestId(request)));
    }

    @ExceptionHandler(RepositoryMutationException.class)
    public ResponseEntity<ApiErrorResponse> mutationFailed(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed", request);
    }

    @ExceptionHandler(SemanticTargetNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> semanticTargetNotFound(
            SemanticTargetNotFoundException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SEMANTIC_TARGET_NOT_FOUND",
                "exact semantic target was not found",
                exception.target(),
                request);
    }

    @ExceptionHandler(ImplementationTargetUnsupportedException.class)
    public ResponseEntity<ApiErrorResponse> implementationTargetUnsupported(
            ImplementationTargetUnsupportedException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IMPLEMENTATION_TARGET_UNSUPPORTED",
                "requested method does not support implementation discovery",
                exception.target(),
                request);
    }

    @ExceptionHandler(SemanticBindingUnresolvedException.class)
    public ResponseEntity<ApiErrorResponse> semanticBindingUnresolved(
            SemanticBindingUnresolvedException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SEMANTIC_BINDING_UNRESOLVED",
                "exact semantic target binding is unresolved",
                exception.target(),
                request);
    }

    @ExceptionHandler(SemanticBindingAmbiguousException.class)
    public ResponseEntity<ApiErrorResponse> semanticBindingAmbiguous(
            SemanticBindingAmbiguousException exception,
            HttpServletRequest request) {
        List<MethodTargetResponse> candidates = exception.candidates().stream()
                .sorted(Comparator.comparing(this::targetSortKey))
                .map(this::target)
                .toList();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.withContext(
                "SEMANTIC_BINDING_AMBIGUOUS",
                "exact semantic target binding is ambiguous",
                null,
                null,
                null,
                target(exception.target()),
                candidates,
                requestId(request)));
    }

    @ExceptionHandler({SemanticEngineNotReadyException.class, SemanticEngineStartFailedException.class})
    public ResponseEntity<ApiErrorResponse> semanticEngineStartFailed(HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "SEMANTIC_ENGINE_START_FAILED", "semantic engine failed to start", request);
    }

    @ExceptionHandler(SemanticRequestTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> semanticRequestTimeout(HttpServletRequest request) {
        return response(HttpStatus.GATEWAY_TIMEOUT,
                "SEMANTIC_REQUEST_TIMEOUT", "semantic request timed out", request);
    }

    @ExceptionHandler(SemanticProtocolException.class)
    public ResponseEntity<ApiErrorResponse> semanticProtocolError(HttpServletRequest request) {
        return response(HttpStatus.BAD_GATEWAY,
                "SEMANTIC_PROTOCOL_ERROR", "semantic protocol request failed", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "request body is invalid", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> internalFailure(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed", request);
    }

    private ResponseEntity<ApiErrorResponse> targetResponse(
            HttpStatus status,
            String errorCode,
            String message,
            MethodTarget target,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrorResponse.withContext(
                errorCode, message, null, null, null, target(target), List.of(), requestId(request)));
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, message, requestId(request)));
    }

    private MethodTargetResponse target(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }

    private String targetSortKey(MethodTarget target) {
        return target.sourceFile() + "|" + target.packageName() + "|" + target.className() + "|"
                + target.methodName() + "|" + String.join(",", target.parameterTypes());
    }

    private String requestId(HttpServletRequest request) {
        return Objects.toString(request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE), "");
    }
}
