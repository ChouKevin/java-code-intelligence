package com.java.semantic.query.application;

import java.util.Optional;

/** Maps expected application failures to stable public error bodies. */
public final class SemanticQueryErrorMapper {

    public SemanticQueryError map(RuntimeException exception) {
        if (exception instanceof RevisionOutdatedException outdated) {
            return new SemanticQueryError("REVISION_OUTDATED", "The requested revision is no longer current.", false,
                    Optional.of(outdated.currentRevision().value()));
        }
        if (exception instanceof RepositoryNotFoundException) {
            return new SemanticQueryError("REPOSITORY_NOT_FOUND", "The requested repository was not found.", false, Optional.empty());
        }
        if (exception instanceof ReviewNotFoundException) {
            return new SemanticQueryError("REVIEW_NOT_FOUND", "The requested review was not found.", false, Optional.empty());
        }
        if (exception instanceof ReviewNotReadyException) {
            return new SemanticQueryError("REVIEW_NOT_READY", "The requested review is not ready.", true, Optional.empty());
        }
        if (exception instanceof ReviewFailedException) {
            return new SemanticQueryError("REVIEW_FAILED", "The requested review failed.", false, Optional.empty());
        }
        if (exception instanceof ReviewContextMismatchException) {
            return new SemanticQueryError("REVIEW_CONTEXT_MISMATCH", "The review context does not match immutable membership.", false, Optional.empty());
        }
        if (exception instanceof MetadataNotPreparedException) {
            return new SemanticQueryError("METADATA_NOT_PREPARED",
                    "Refresh metadata with Indexer refresh_repository_metadata for the requested repository and branch, "
                            + "saving a new requestId first; then query the returned get_job identity. Query does not prepare evidence.",
                    false, Optional.empty());
        }
        if (exception instanceof GitEvidenceNotFoundException) {
            return new SemanticQueryError("GIT_EVIDENCE_NOT_FOUND", "The requested Git evidence was not found.", false, Optional.empty());
        }
        if (exception instanceof GitEvidenceNotReadyException) {
            return new SemanticQueryError("GIT_EVIDENCE_NOT_READY", "The requested Git evidence is not ready.", true, Optional.empty());
        }
        if (exception instanceof CodeFactNotFoundException) {
            return new SemanticQueryError("FACT_NOT_FOUND", "The requested fact was not found.", false, Optional.empty());
        }
        if (exception instanceof CodeFactKindMismatchException) {
            return new SemanticQueryError("FACT_KIND_MISMATCH", "The fact is not valid for this operation.", false, Optional.empty());
        }
        if (exception instanceof IndexNotReadyException || exception instanceof IndexContractMismatchException
                || exception instanceof SemanticIndexUnavailableException || exception instanceof com.mongodb.MongoException
                || exception instanceof org.springframework.dao.DataAccessException) {
            return new SemanticQueryError("INDEX_UNAVAILABLE", "The semantic index is temporarily unavailable.", true, Optional.empty());
        }
        if (exception instanceof InvalidCodeFactQueryException || exception instanceof CodeFactKindUnsupportedException
                || exception instanceof IllegalArgumentException) {
            return new SemanticQueryError("INVALID_ARGUMENT", "The request is invalid.", false, Optional.empty());
        }
        throw exception;
    }

}
