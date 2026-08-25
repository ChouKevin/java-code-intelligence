package com.java.semantic.model.query;

/** Bounded page metadata for a relation query. */
public record PublishedRelationPage(int offset, int limit, int returnedCount, int totalCount) {

    public PublishedRelationPage {
        if (offset < 0 || limit < 1 || returnedCount < 0 || totalCount < returnedCount) {
            throw new IllegalArgumentException("relation page counts are invalid");
        }
    }

    public boolean hasMore() {
        return offset + returnedCount < totalCount;
    }
}
