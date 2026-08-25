package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.IndexContractMismatchException;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.RepositoryNotFoundException;
import com.java.semantic.query.application.SemanticIndexUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryApiExceptionHandlerTest {

    @Test
    void pointer_switch_returns_the_stable_requested_and_current_revision_failure() {
        RevisionOutdatedException exception = new RevisionOutdatedException(RepositoryId.of("orders"),
                new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40)));

        ResponseEntity<QueryApiExceptionHandler.RevisionOutdatedResponse> response =
                new QueryApiExceptionHandler().revisionOutdated(exception);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("REVISION_OUTDATED", response.getBody().code());
        assertEquals("orders", response.getBody().repositoryId());
        assertEquals("a".repeat(40), response.getBody().requestedRevision());
        assertEquals("b".repeat(40), response.getBody().currentRevision());
        assertEquals("Retry with currentRevision.", response.getBody().retryGuidance());
    }

    @Test
    void typed_query_failures_keep_their_stable_code_and_storage_is_retryable() {
        QueryApiExceptionHandler handler = new QueryApiExceptionHandler();
        assertFailure(handler, new RepositoryNotFoundException(), HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", false);
        assertFailure(handler, new CodeFactNotFoundException(), HttpStatus.NOT_FOUND, "CODE_FACT_NOT_FOUND", false);
        assertFailure(handler, new IndexNotReadyException(), HttpStatus.SERVICE_UNAVAILABLE, "INDEX_NOT_READY", true);
        assertFailure(handler, new IndexContractMismatchException(), HttpStatus.SERVICE_UNAVAILABLE, "INDEX_CONTRACT_MISMATCH", true);
        assertFailure(handler, new SemanticIndexUnavailableException(new IllegalStateException("database hostname")),
                HttpStatus.SERVICE_UNAVAILABLE, "SEMANTIC_INDEX_UNAVAILABLE", true);
    }

    private static void assertFailure(QueryApiExceptionHandler handler, RuntimeException exception, HttpStatus status,
                                      String code, boolean retryable) {
        ResponseEntity<QueryApiExceptionHandler.QueryFailureResponse> response = handler.failure(exception);
        assertEquals(status, response.getStatusCode());
        assertEquals(code, response.getBody().code());
        assertEquals(retryable, response.getBody().retryable());
        org.junit.jupiter.api.Assertions.assertNull(response.getBody().message());
    }
}
