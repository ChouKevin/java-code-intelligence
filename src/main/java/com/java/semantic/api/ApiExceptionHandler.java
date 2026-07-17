package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.repository.application.ImmutableFixtureException;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.InvalidRepositoryIdException;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 將 domain/application 例外轉成固定且安全的 HTTP 錯誤 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidRepositoryIdException.class)
    public ResponseEntity<ApiErrorResponse> invalidRepositoryId() {
        return response(HttpStatus.BAD_REQUEST, "REPOSITORY_ID_INVALID", "repository id is invalid");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "request body is invalid");
    }

    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> repositoryNotFound() {
        return response(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "repository is not configured");
    }

    @ExceptionHandler(RepositoryBusyException.class)
    public ResponseEntity<ApiErrorResponse> repositoryBusy() {
        return response(HttpStatus.CONFLICT, "REPOSITORY_BUSY", "repository is busy");
    }

    @ExceptionHandler(RepositoryNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> repositoryNotReady() {
        return response(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "repository is not ready");
    }

    @ExceptionHandler(ImmutableFixtureException.class)
    public ResponseEntity<ApiErrorResponse> immutableFixture() {
        return response(
                HttpStatus.CONFLICT,
                "REPOSITORY_IMMUTABLE_FIXTURE",
                "local fixture repositories are immutable");
    }

    @ExceptionHandler(RepositoryRevisionMismatchException.class)
    public ResponseEntity<ApiErrorResponse> revisionMismatch(
            RepositoryRevisionMismatchException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiErrorResponse.revisionMismatch(
                        exception.getCurrentRevision().value(),
                        exception.getExpectedRevision().value()));
    }

    @ExceptionHandler(RepositoryMutationException.class)
    public ResponseEntity<ApiErrorResponse> mutationFailed() {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "REPOSITORY_MUTATION_FAILED",
                "repository mutation failed");
    }

    @ExceptionHandler(SemanticAmbiguousMethodException.class)
    public ResponseEntity<ApiErrorResponse> ambiguousMethod(SemanticAmbiguousMethodException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiErrorResponse.ambiguousMethod(
                        "method signature is ambiguous; provide a full signature",
                        exception.candidates()));
    }

    @ExceptionHandler(SemanticSymbolNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> symbolNotFound() {
        return response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SEMANTIC_SYMBOL_NOT_FOUND",
                "requested symbol was not found in the workspace");
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String errorCode,
            String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, message));
    }
}
