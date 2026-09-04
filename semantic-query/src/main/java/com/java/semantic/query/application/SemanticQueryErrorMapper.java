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
        if (exception instanceof CodeFactNotFoundException) {
            return new SemanticQueryError("FACT_NOT_FOUND", "The requested fact was not found.", false, Optional.empty());
        }
        if (exception instanceof CodeFactKindMismatchException) {
            return new SemanticQueryError("FACT_KIND_MISMATCH", "The fact is not valid for this operation.", false, Optional.empty());
        }
        if (exception instanceof IndexNotReadyException || exception instanceof IndexContractMismatchException
                || exception instanceof SemanticIndexUnavailableException) {
            return new SemanticQueryError("INDEX_UNAVAILABLE", "The semantic index is temporarily unavailable.", true, Optional.empty());
        }
        if (exception instanceof InvalidCodeFactQueryException || exception instanceof CodeFactKindUnsupportedException
                || exception instanceof IllegalArgumentException) {
            return new SemanticQueryError("INVALID_ARGUMENT", "The request is invalid.", false, Optional.empty());
        }
        throw exception;
    }

}
