package com.java.semantic.query.application;

/** A known fact cannot be used by the requested operation because its kind differs. */
public final class CodeFactKindMismatchException extends RuntimeException {
    public CodeFactKindMismatchException() {
        super("FACT_KIND_MISMATCH");
    }
}
