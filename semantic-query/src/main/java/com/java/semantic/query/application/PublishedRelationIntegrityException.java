package com.java.semantic.query.application;

/** A sealed generation contains a relation whose required internal symbol is absent. */
public final class PublishedRelationIntegrityException extends RuntimeException {

    public PublishedRelationIntegrityException(String message) {
        super(message);
    }
}
