package com.java.semantic.repository.domain;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** 在讀鎖生命週期內可安全使用的儲存庫快照 */
public record RepositorySnapshot(
        RepositoryId repositoryId,
        Path root,
        RepositoryRevision revision) {

    public RepositorySnapshot {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(root, "root is required");
        Objects.requireNonNull(revision, "revision is required");
    }

    /** Converts a semantic file URI into a normalized source file relative to this snapshot root. */
    public Optional<String> relativeSourceFile(String uri) {
        try {
            Path source = Path.of(URI.create(uri)).normalize();
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (!source.startsWith(normalizedRoot)) {
                return Optional.empty();
            }
            return Optional.of(normalizedRoot.relativize(source).toString().replace('\\', '/'));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
