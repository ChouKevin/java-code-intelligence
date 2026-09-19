package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import java.util.Objects;

/** Annotates a review preparation failure with its public durable job category. */
public final class ReviewPreparationException extends RuntimeException {
    private final IndexFailureCategory category;

    public ReviewPreparationException(IndexFailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "failure category is required");
    }

    public ReviewPreparationException(IndexFailureCategory category, String message) {
        this(category, message, null);
    }

    public IndexFailureCategory category() {
        return category;
    }
}
