package com.java.semantic.indexer.store;

import java.util.Objects;
import java.util.function.Supplier;

/** Executes the versioned, idempotent Mongo schema bootstrap for deployment automation. */
public final class SchemaBootstrapCommand {
    private final Supplier<String> bootstrap;

    public SchemaBootstrapCommand(Supplier<String> bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap is required");
    }

    public String run() {
        return bootstrap.get();
    }
}
