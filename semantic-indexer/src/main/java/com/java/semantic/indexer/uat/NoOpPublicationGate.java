package com.java.semantic.indexer.uat;

/** Production publication remains immediate. */
public final class NoOpPublicationGate implements PublicationGate {
    @Override
    public void awaitPublication() {
        // Intentionally empty.
    }

    @Override
    public void abortPublication() {
        // Intentionally empty.
    }
}
