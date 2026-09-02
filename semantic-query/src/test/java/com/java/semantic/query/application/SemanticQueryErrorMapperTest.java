package com.java.semantic.query.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticQueryErrorMapperTest {

    private final SemanticQueryErrorMapper mapper = new SemanticQueryErrorMapper();

    @Test
    void maps_outdated_revision_with_only_current_revision_detail() {
        SemanticQueryError error = mapper.map(new RevisionOutdatedException(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40))));

        assertEquals("REVISION_OUTDATED", error.code());
        assertFalse(error.retryable());
        assertEquals(Optional.of("b".repeat(40)), error.currentRevision());
    }

    @Test
    void maps_missing_fact_to_safe_not_found_error() {
        SemanticQueryError error = mapper.map(new CodeFactNotFoundException());

        assertEquals(new SemanticQueryError("FACT_NOT_FOUND", "The requested fact was not found.", false, Optional.empty()), error);
    }

    @Test
    void collapses_index_contract_and_storage_failures_without_internal_details() {
        SemanticQueryError contract = mapper.map(new IndexContractMismatchException());
        SemanticQueryError storage = mapper.map(new SemanticIndexUnavailableException(new IllegalStateException("mongo secret host")));

        assertEquals("INDEX_UNAVAILABLE", contract.code());
        assertEquals(contract, storage);
        assertTrue(contract.retryable());
        assertFalse(contract.message().contains("mongo"));
        assertEquals(Optional.empty(), contract.currentRevision());
    }
}
