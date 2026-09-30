package com.java.semantic.indexer.job;

import com.java.semantic.model.review.ReviewSelection;
import java.util.Objects;
import java.util.Optional;

/** Original validated inputs; applying a default branch must not rewrite this value. */
public record PreparationRequest(PreparationRequestId requestId, PreparationOperation operation,
        Optional<String> branch, Optional<ReviewSelection> selection) {
    public PreparationRequest {
        requestId = Objects.requireNonNull(requestId, "preparation request id is required");
        operation = Objects.requireNonNull(operation, "preparation operation is required");
        branch = Objects.requireNonNull(branch, "requested branch is required");
        selection = Objects.requireNonNull(selection, "requested selection is required");
        if (branch.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("requested branch must not be blank");
        }
        if (operation != PreparationOperation.REFRESH_REPOSITORY_METADATA && branch.isPresent()) {
            throw new IllegalArgumentException("only metadata preparation accepts a branch");
        }
        if ((operation == PreparationOperation.PREPARE_REVIEW) != selection.isPresent()) {
            throw new IllegalArgumentException("only review preparation requires a selection");
        }
    }

    public static PreparationRequest codebase(PreparationRequestId requestId) {
        return new PreparationRequest(requestId, PreparationOperation.PREPARE_CODEBASE, Optional.empty(), Optional.empty());
    }

    public static PreparationRequest metadata(PreparationRequestId requestId, Optional<String> branch) {
        return new PreparationRequest(requestId, PreparationOperation.REFRESH_REPOSITORY_METADATA, branch, Optional.empty());
    }

    public static PreparationRequest review(PreparationRequestId requestId, ReviewSelection selection) {
        return new PreparationRequest(requestId, PreparationOperation.PREPARE_REVIEW, Optional.empty(), Optional.of(selection));
    }
}
