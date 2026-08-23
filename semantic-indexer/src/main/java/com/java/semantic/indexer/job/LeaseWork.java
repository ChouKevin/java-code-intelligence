package com.java.semantic.indexer.job;

@FunctionalInterface
public interface LeaseWork {
    void run(LeaseGuard guard);
}
