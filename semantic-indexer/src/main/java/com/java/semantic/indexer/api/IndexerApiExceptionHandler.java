package com.java.semantic.indexer.api;

import com.java.semantic.indexer.application.IndexerPreparationErrorMapper;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class IndexerApiExceptionHandler {
    private final IndexerPreparationErrorMapper errors = new IndexerPreparationErrorMapper();

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> applicationFailure(RuntimeException exception) {
        IndexerPreparationErrorMapper.Failure failure = errors.map(exception);
        return ResponseEntity.status(failure.status()).body(failure.body());
    }
}
