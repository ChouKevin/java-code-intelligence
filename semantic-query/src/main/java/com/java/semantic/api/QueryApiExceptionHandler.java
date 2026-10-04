package com.java.semantic.api;

import com.java.semantic.query.application.SemanticQueryError;
import com.java.semantic.query.application.SemanticQueryErrorMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
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

/** HTTP status mechanics over the same source failure body returned by MCP. */
@RestControllerAdvice
public final class QueryApiExceptionHandler {

    private final SemanticQueryErrorMapper errorMapper;

    public QueryApiExceptionHandler() {
        this(new SemanticQueryErrorMapper());
    }

    QueryApiExceptionHandler(SemanticQueryErrorMapper errorMapper) {
        this.errorMapper = Objects.requireNonNull(errorMapper, "source error mapper");
    }

    @ExceptionHandler(RuntimeException.class)
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
            case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
            case "REPOSITORY_NOT_FOUND", "REVISION_NOT_PREPARED", "SOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SOURCE_NOT_PREPARED" -> HttpStatus.CONFLICT;
            case "SOURCE_UNSUPPORTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "SOURCE_BUSY", "SOURCE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "SOURCE_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> throw new IllegalArgumentException("unknown source error code");
        };
    }
}
