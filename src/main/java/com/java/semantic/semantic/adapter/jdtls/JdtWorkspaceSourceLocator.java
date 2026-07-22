package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Sole adapter boundary for converting repository source identities and local JDT URIs. */
final class JdtWorkspaceSourceLocator {

    String sourceUri(RepositorySnapshot snapshot, MethodTarget target) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(target, "target is required");
        Path root = realRoot(snapshot);
        Path source = realPath(root.resolve(target.sourceFile()));
        requireContainedRegularFile(root, source);
        return source.toUri().toString();
    }

    Optional<Path> localSource(RepositorySnapshot snapshot, String uriText) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        URI uri = parse(uriText);
        if ("jdt".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new SemanticProtocolException();
        }
        Path root = realRoot(snapshot);
        Path source;
        try {
            source = realPath(Path.of(uri));
        } catch (RuntimeException exception) {
            throw new SemanticProtocolException();
        }
        requireContainedRegularFile(root, source);
        return Optional.of(source);
    }

    String localSourceUri(RepositorySnapshot snapshot, String uriText) {
        return localSource(snapshot, uriText)
                .map(source -> source.toUri().toString())
                .orElseThrow(SemanticProtocolException::new);
    }

    boolean isExternal(RepositorySnapshot snapshot, String uriText) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        URI uri = parse(uriText);
        if ("jdt".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new SemanticProtocolException();
        }
        Path root = realRoot(snapshot);
        try {
            Path source = realPath(Path.of(uri));
            requireContainedRegularFile(root, source);
            return false;
        } catch (RuntimeException exception) {
            throw new SemanticProtocolException();
        }
    }

    private Path realRoot(RepositorySnapshot snapshot) {
        return realPath(snapshot.root());
    }

    private URI parse(String uriText) {
        try {
            return URI.create(uriText);
        } catch (RuntimeException exception) {
            throw new SemanticProtocolException();
        }
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw new SemanticProtocolException();
        }
    }

    private void requireContainedRegularFile(Path root, Path source) {
        try {
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                throw new SemanticProtocolException();
            }
        } catch (IllegalArgumentException exception) {
            throw new SemanticProtocolException();
        }
    }
}
