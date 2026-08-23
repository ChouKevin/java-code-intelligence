package com.java.semantic.indexer.job;

@FunctionalInterface
public interface LeaseGuard {
    void requireHeld();
}
