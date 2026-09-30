package com.java.semantic.query.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.List;

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

        assertEquals("FACT_NOT_FOUND", error.code());
        assertFalse(error.retryable());
        assertTrue(error.currentRevision().isEmpty());
    }

    @Test
    void collapses_index_contract_and_storage_failures_without_internal_details() {
        SemanticQueryError contract = mapper.map(new IndexContractMismatchException());
        SemanticQueryError storage = mapper.map(new SemanticIndexUnavailableException(new IllegalStateException("mongo secret host")));
        SemanticQueryError nativeStorage = mapper.map(new com.mongodb.MongoException("mongo secret host"));
        SemanticQueryError springStorage = mapper.map(new org.springframework.dao.DataAccessResourceFailureException("password"));

        assertEquals("INDEX_UNAVAILABLE", contract.code());
        assertEquals(contract, storage);
        assertEquals(storage, nativeStorage);
        assertEquals(storage, springStorage);
        assertTrue(contract.retryable());
        assertFalse(contract.message().contains("mongo"));
        assertEquals(Optional.empty(), contract.currentRevision());
    }

    @Test
    void maps_review_lifecycle_and_context_failures_to_stable_errors() {
        List<SemanticQueryError> errors = List.of(
                mapper.map(new ReviewNotFoundException()),
                mapper.map(new ReviewNotReadyException()),
                mapper.map(new ReviewFailedException()),
                mapper.map(new ReviewContextMismatchException()));

        assertEquals(List.of("REVIEW_NOT_FOUND", "REVIEW_NOT_READY", "REVIEW_FAILED", "REVIEW_CONTEXT_MISMATCH"),
                errors.stream().map(SemanticQueryError::code).toList());
        assertEquals(List.of(false, true, false, false), errors.stream().map(SemanticQueryError::retryable).toList());
        SemanticQueryError metadata = mapper.map(new MetadataNotPreparedException());
        assertEquals("METADATA_NOT_PREPARED", metadata.code());
        assertFalse(metadata.retryable());
    }
}
