package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CodeFactNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
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
        assertEquals(new SemanticQueryError("REVISION_OUTDATED", "The requested revision is no longer current.", false,
                Optional.of("b".repeat(40))), response.getBody());
    }

    @Test
    void maps_fact_not_found_without_transport_owned_fields() {
        ResponseEntity<?> response = new QueryApiExceptionHandler().failure(new CodeFactNotFoundException());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(new SemanticQueryError("FACT_NOT_FOUND", "The requested fact was not found.", false, Optional.empty()),
                response.getBody());
    }
}
