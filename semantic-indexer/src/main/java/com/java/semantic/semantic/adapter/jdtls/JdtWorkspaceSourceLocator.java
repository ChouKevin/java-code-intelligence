package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.repository.domain.RepositorySourceContainmentResult;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** 唯一負責轉換 repository source identity 與分類 JDT URI 的 adapter 邊界 */
final class JdtWorkspaceSourceLocator {

    private final RepositorySourceContainment containment = new RepositorySourceContainment();

    String sourceUri(RepositorySnapshot snapshot, MethodTarget target) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(target, "target is required");
        return sourceUri(snapshot, target.sourceFile());
    }

    String sourceUri(RepositorySnapshot snapshot, String sourceFile) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(sourceFile, "sourceFile is required");
        RepositorySourceContainmentResult result = containment.classify(
                snapshot.root(), snapshot.root().resolve(sourceFile));
        if (result instanceof RepositorySourceContainmentResult.ContainedSource source
                && source.sourceFile().equals(sourceFile)) {
            return source.realPath().toUri().toString();
        }
        throw new SemanticProtocolException();
    }

    SemanticSourceClassification classify(RepositorySnapshot snapshot, String uriText) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(uriText, "uriText is required");
        URI uri;
        try {
            uri = URI.create(uriText);
        } catch (RuntimeException exception) {
            if (uriText.regionMatches(true, 0, "file:", 0, "file:".length())) {
                return SemanticSourceClassification.UnprovableUri.INSTANCE;
            }
            throw new SemanticProtocolException();
        }
        if ("jdt".equalsIgnoreCase(uri.getScheme())) {
            return SemanticSourceClassification.OutsideRepository.INSTANCE;
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new SemanticProtocolException();
        }
        Path candidate;
        try {
            candidate = Path.of(uri);
        } catch (RuntimeException exception) {
            return SemanticSourceClassification.UnprovableUri.INSTANCE;
        }
        RepositorySourceContainmentResult result = containment.classify(snapshot.root(), candidate);
        return switch (result) {
            case RepositorySourceContainmentResult.ContainedSource source ->
                    new SemanticSourceClassification.LocalSource(source.sourceFile());
            case RepositorySourceContainmentResult.OutsideRepository ignored ->
                    SemanticSourceClassification.OutsideRepository.INSTANCE;
            case RepositorySourceContainmentResult.UnprovableSource ignored ->
                    SemanticSourceClassification.UnprovableUri.INSTANCE;
        };
    }

    Optional<Path> localSource(RepositorySnapshot snapshot, String uriText) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        SemanticSourceClassification classification = classify(snapshot, uriText);
        return switch (classification) {
            case SemanticSourceClassification.LocalSource local ->
                    containedPath(snapshot, local.sourceFile());
            case SemanticSourceClassification.OutsideRepository ignored -> Optional.empty();
            case SemanticSourceClassification.UnprovableUri ignored -> throw new SemanticProtocolException();
        };
    }

    String localSourceUri(RepositorySnapshot snapshot, String uriText) {
        return localSource(snapshot, uriText)
                .map(source -> source.toUri().toString())
                .orElseThrow(SemanticProtocolException::new);
    }

    boolean isExternal(RepositorySnapshot snapshot, String uriText) {
        return switch (classify(snapshot, uriText)) {
            case SemanticSourceClassification.LocalSource ignored -> false;
            case SemanticSourceClassification.OutsideRepository ignored -> true;
            case SemanticSourceClassification.UnprovableUri ignored -> throw new SemanticProtocolException();
        };
    }

    private Optional<Path> containedPath(RepositorySnapshot snapshot, String sourceFile) {
        RepositorySourceContainmentResult result = containment.classify(
                snapshot.root(), snapshot.root().resolve(sourceFile));
        if (result instanceof RepositorySourceContainmentResult.ContainedSource source
                && source.sourceFile().equals(sourceFile)) {
            return Optional.of(source.realPath());
        }
        throw new SemanticProtocolException();
    }
}
