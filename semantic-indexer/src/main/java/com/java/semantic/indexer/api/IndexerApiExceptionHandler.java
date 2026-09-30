package com.java.semantic.indexer.api;

import com.java.semantic.indexer.application.IndexerPreparationErrorMapper;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.store.SemanticIndexUnavailableException;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class IndexerApiExceptionHandler {
    private final IndexerPreparationErrorMapper errors = new IndexerPreparationErrorMapper();
    @ExceptionHandler({IllegalArgumentException.class, IndexJobAlreadyActiveException.class,
            IndexJobNotFoundException.class, PreparationRequestNotFoundException.class, PreparationRequestReusedException.class,
            RepositoryNotFoundException.class, RepositoryBusyException.class, RepositoryMutationException.class,
            SemanticIndexUnavailableException.class, org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, Object>> applicationFailure(RuntimeException exception) {
        IndexerPreparationErrorMapper.Failure failure = errors.map(exception);
        return ResponseEntity.status(failure.status()).body(failure.body());
    }
}
