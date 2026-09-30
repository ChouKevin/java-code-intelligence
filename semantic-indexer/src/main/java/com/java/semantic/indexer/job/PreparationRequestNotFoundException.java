package com.java.semantic.indexer.job;

/** Absence of a visible insert does not establish that submission was rejected. */
public final class PreparationRequestNotFoundException extends RuntimeException {
    public PreparationRequestNotFoundException() {
        super("preparation acceptance is unknown; continue looking up the original request, do not resubmit");
    }
}
