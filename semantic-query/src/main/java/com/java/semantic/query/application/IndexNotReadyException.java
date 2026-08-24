package com.java.semantic.query.application;

public final class IndexNotReadyException extends RuntimeException {
    public IndexNotReadyException() {
        super("INDEX_NOT_READY");
    }
}
