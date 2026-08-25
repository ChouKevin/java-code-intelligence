package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.RevisionOutdatedException;
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
}
