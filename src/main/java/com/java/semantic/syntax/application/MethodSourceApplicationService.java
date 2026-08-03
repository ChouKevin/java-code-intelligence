package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.MethodImplementationEligibilityPolicy;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.RevisionPinnedSourceRangeReader;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;

import java.util.Objects;
import java.util.Optional;

/** 在固定 repository snapshot 解析方法宣告後讀取首個 bounded 原始碼區段 */
public final class MethodSourceApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final CanonicalMethodDeclarationResolver declarationResolver;
    private final RevisionPinnedSourceRangeReader sourceRangeReader;

    public MethodSourceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            CanonicalMethodDeclarationResolver declarationResolver,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.declarationResolver = Objects.requireNonNull(declarationResolver, "declarationResolver is required");
        this.sourceRangeReader = Objects.requireNonNull(sourceRangeReader, "sourceRangeReader is required");
    }

    /** 以 expected revision 的讀鎖解析唯一宣告並只讀取第一個原始碼區段 */
    public MethodSourceResult read(MethodSourceQuery query) {
        MethodSourceQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                requiredQuery.repositoryId(),
                Optional.of(requiredQuery.expectedRevision()),
                snapshot -> readSnapshot(snapshot, requiredQuery));
    }

    private MethodSourceResult readSnapshot(RepositorySnapshot snapshot, MethodSourceQuery query) {
        RepositorySyntax syntax = repositorySyntaxProvider.get(snapshot);
        MethodTarget resolvedTarget = resolvedTarget(syntax, query.target());
        SourceTypeMetadata ownerType = syntax.sourceTypes().stream()
                .filter(metadata -> metadata.members().methods().stream()
                        .anyMatch(method -> method.analysisTarget().target().filter(resolvedTarget::equals).isPresent()))
                .findFirst()
                .orElseThrow(() -> new SemanticTargetNotFoundException(query.target()));
        SourceMethodMetadata declaration = ownerType.members().methods().stream()
                .filter(method -> method.analysisTarget().target().filter(resolvedTarget::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new SemanticTargetNotFoundException(query.target()));
        SourceRange declarationLocation = declaration.declarationLocation();
        SourceRangeSegment segment = sourceRangeReader.read(snapshot, declarationLocation, 0, declarationLocation);
        return new MethodSourceResult(
                snapshot.repositoryId(), snapshot.revision(), declarationLocation, segment,
                MethodImplementationEligibilityPolicy.isEligible(ownerType, declaration));
    }

    private MethodTarget resolvedTarget(RepositorySyntax syntax, MethodTarget target) {
        MethodTargetResolution resolution = declarationResolver.resolve(syntax, target);
        return switch (resolution.status()) {
            case RESOLVED -> resolution.target().orElseThrow(() -> new SemanticTargetNotFoundException(target));
            case UNRESOLVED -> throw new SemanticTargetNotFoundException(target);
            case AMBIGUOUS -> throw new SemanticBindingAmbiguousException(target, resolution.candidates());
        };
    }
}
