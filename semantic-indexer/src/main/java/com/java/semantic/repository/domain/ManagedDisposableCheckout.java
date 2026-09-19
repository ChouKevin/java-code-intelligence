package com.java.semantic.repository.domain;

import java.nio.file.Path;
import java.util.Objects;

/** Capability minted by the configured repository runtime for its disposable checkout only. */
public final class ManagedDisposableCheckout {
    private final Path root;

    ManagedDisposableCheckout(Path root) {
        this.root = Objects.requireNonNull(root, "checkout root is required").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }
}
