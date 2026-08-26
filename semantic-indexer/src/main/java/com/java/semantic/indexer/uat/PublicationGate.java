package com.java.semantic.indexer.uat;

/** A job-scoped build reaches this boundary only after sealing and before pointer publication. */
public interface PublicationGate {
    void awaitPublication();
    void abortPublication();
}
