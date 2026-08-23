package com.java.semantic.indexer.store;

/** The repository coordinator no longer matches the exact publication compare-and-set input. */
public final class PublicationConflictException extends RuntimeException {

    public PublicationConflictException() {
        super("SEMANTIC_INDEX_PUBLICATION_CONFLICT");
    }
}
