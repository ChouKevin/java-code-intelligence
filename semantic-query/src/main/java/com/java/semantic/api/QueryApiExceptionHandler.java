package com.java.semantic.api;

import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.CodeFactKindUnsupportedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.IndexContractMismatchException;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.InvalidCodeFactQueryException;
import com.java.semantic.query.application.RepositoryNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticIndexUnavailableException;
import com.java.semantic.query.application.SemanticQueryError;
import com.java.semantic.query.application.SemanticQueryErrorMapper;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps HTTP status mechanics onto the shared Semantic application error body. */
@RestControllerAdvice
public final class QueryApiExceptionHandler {
    private final SemanticQueryErrorMapper errorMapper;

    public QueryApiExceptionHandler() {
        this(new SemanticQueryErrorMapper());
    }

    QueryApiExceptionHandler(SemanticQueryErrorMapper errorMapper) {
        this.errorMapper = java.util.Objects.requireNonNull(errorMapper, "semantic query error mapper is required");
    }

    @ExceptionHandler({RevisionOutdatedException.class, RepositoryNotFoundException.class, CodeFactNotFoundException.class,
            CodeFactKindMismatchException.class, IndexNotReadyException.class, IndexContractMismatchException.class,
            SemanticIndexUnavailableException.class, InvalidCodeFactQueryException.class,
            CodeFactKindUnsupportedException.class, IllegalArgumentException.class})
    public ResponseEntity<SemanticQueryError> failure(RuntimeException exception) {
        SemanticQueryError error = errorMapper.map(exception);
        return ResponseEntity.status(status(error)).body(error);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, BindException.class,
            MissingServletRequestParameterException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<SemanticQueryError> requestFailure(Exception exception) {
        return failure(new IllegalArgumentException("invalid HTTP request", exception));
    }

    private static HttpStatus status(SemanticQueryError error) {
        return switch (error.code()) {
            case "REPOSITORY_NOT_FOUND", "FACT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "REVISION_OUTDATED" -> HttpStatus.CONFLICT;
            case "INDEX_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INVALID_ARGUMENT", "FACT_KIND_MISMATCH" -> HttpStatus.BAD_REQUEST;
            default -> throw new IllegalArgumentException("unknown Semantic Query error code");
        };
    }
}
