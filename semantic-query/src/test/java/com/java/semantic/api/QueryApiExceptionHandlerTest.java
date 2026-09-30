package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.ReviewContextMismatchException;
import com.java.semantic.query.application.ReviewFailedException;
import com.java.semantic.query.application.ReviewNotFoundException;
import com.java.semantic.query.application.ReviewNotReadyException;
import com.java.semantic.query.application.SemanticQueryError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryApiExceptionHandlerTest {

    @Test
    void maps_revision_outdated_to_the_shared_safe_application_error() {
        RevisionOutdatedException exception = new RevisionOutdatedException(RepositoryId.of("orders"),
                new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40)));

        ResponseEntity<?> response = new QueryApiExceptionHandler().failure(exception);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("REVISION_OUTDATED", ((SemanticQueryError) response.getBody()).code());
        assertEquals(Optional.of("b".repeat(40)), ((SemanticQueryError) response.getBody()).currentRevision());
    }

    @Test
    void maps_fact_not_found_without_transport_owned_fields() {
        ResponseEntity<?> response = new QueryApiExceptionHandler().failure(new CodeFactNotFoundException());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("FACT_NOT_FOUND", ((SemanticQueryError) response.getBody()).code());
    }

    @Test
    void maps_review_lifecycle_and_context_failures_to_their_public_statuses() {
        QueryApiExceptionHandler handler = new QueryApiExceptionHandler();

        assertEquals(HttpStatus.NOT_FOUND, handler.failure(new ReviewNotFoundException()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, handler.failure(new ReviewNotReadyException()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, handler.failure(new ReviewFailedException()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, handler.failure(new ReviewContextMismatchException()).getStatusCode());
    }
}
