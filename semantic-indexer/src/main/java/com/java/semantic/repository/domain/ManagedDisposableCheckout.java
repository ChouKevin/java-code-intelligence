package com.java.semantic.repository.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
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

    public List<Path> validateTree(Path candidate) throws IOException {
        Path checkout = validateBoundaryPath(candidate);
        if (!Files.exists(checkout, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed checkout must be a real directory");
        }
        Path realRoot = checkout.toRealPath();
        Path gitDirectory = checkout.resolve(".git");
        try (Stream<Path> tree = Files.walk(checkout)) {
            List<Path> entries = tree.toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    if (entry.startsWith(gitDirectory)) {
                        throw new IOException("managed checkout Git metadata contains a symbolic link");
                    }
                    continue;
                }
                if (!entry.toRealPath().startsWith(realRoot)) {
                    throw new IOException("managed checkout tree contains an escaping path");
                }
            }
            return List.copyOf(entries);
        }
    }

    public void validate(Path candidate) throws IOException {
        validateTree(candidate);
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
