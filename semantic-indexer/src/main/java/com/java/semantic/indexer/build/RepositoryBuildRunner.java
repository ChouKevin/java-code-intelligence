package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import java.util.Objects;

/** Owns the closeable resource scope for one admitted repository build. */
public final class RepositoryBuildRunner {
    private final BuildScopeFactory scopes;

    public RepositoryBuildRunner(BuildScopeFactory scopes) {
        this.scopes = Objects.requireNonNull(scopes, "build scopes are required");
    }

    public void run(IndexJob job) {
        Objects.requireNonNull(job, "job is required");
        try (BuildScope scope = Objects.requireNonNull(scopes.open(job), "build scope is required")) {
            scope.build();
        }
    }

    @FunctionalInterface
    public interface BuildScopeFactory {
        BuildScope open(IndexJob job);
    }

    public interface BuildScope extends AutoCloseable {
        void build();

        @Override
        void close();
    }
}
