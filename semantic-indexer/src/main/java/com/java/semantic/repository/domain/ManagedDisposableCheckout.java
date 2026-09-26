package com.java.semantic.repository.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/** Capability minted by the configured repository runtime for its disposable checkout only. */
public final class ManagedDisposableCheckout {
    private final Path root;
    private final Path managedParent;

    ManagedDisposableCheckout(Path root, Path managedParent) {
        this.root = Objects.requireNonNull(root, "checkout root is required").toAbsolutePath().normalize();
        this.managedParent = Objects.requireNonNull(managedParent, "managed checkout parent is required")
                .toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path managedParent() {
        return managedParent;
    }

    public void validate(Path candidate) throws IOException {
        Path checkout = validateBoundaryPath(candidate);
        if (Files.exists(checkout, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> tree = Files.walk(checkout)) {
                if (tree.anyMatch(Files::isSymbolicLink)) {
                    throw new IOException("managed checkout tree contains a symbolic link");
                }
            }
        }
    }

    public void validateBoundary(Path candidate) throws IOException {
        validateBoundaryPath(candidate);
    }

    private Path validateBoundaryPath(Path candidate) throws IOException {
        Path checkout = candidate.toAbsolutePath().normalize();
        if (!checkout.equals(root) || !managedParent.equals(checkout.getParent())) {
            throw new IOException("managed checkout root is invalid");
        }
        validateAncestors(checkout);
        if (!Files.isDirectory(managedParent, LinkOption.NOFOLLOW_LINKS)
                || !managedParent.toRealPath().equals(managedParent)) {
            throw new IOException("managed checkout parent must be a real canonical directory");
        }
        return checkout;
    }

    public static void validateAncestors(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        for (Path segment : normalized) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("managed path contains a symbolic link");
            }
        }
    }
}
