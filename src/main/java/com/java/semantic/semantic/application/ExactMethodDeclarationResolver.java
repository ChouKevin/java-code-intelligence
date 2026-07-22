package com.java.semantic.semantic.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resolves a declaration only from the syntax extractor's canonical target proof. */
public final class ExactMethodDeclarationResolver {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, ExactMethodDeclarationResolver::compareParameters);

    public SemanticDeclarationAnchor resolve(RepositorySyntax syntax, MethodTarget target) {
        Objects.requireNonNull(syntax, "syntax is required");
        Objects.requireNonNull(target, "target is required");
        List<Declaration> declarations = declarationsFor(syntax, target);
        List<MethodTarget> ambiguousCandidates = declarations.stream()
                .map(Declaration::resolution)
                .filter(resolution -> AnalysisTargetStatus.AMBIGUOUS.equals(resolution.status()))
                .filter(resolution -> resolution.candidates().contains(target))
                .flatMap(resolution -> resolution.candidates().stream())
                .distinct()
                .sorted(TARGET_ORDER)
                .toList();
        if (ambiguousCandidates.size() > 1) {
            throw new SemanticBindingAmbiguousException(target, ambiguousCandidates);
        }
        List<Declaration> exact = declarations.stream()
                .filter(declaration -> AnalysisTargetStatus.RESOLVED.equals(declaration.resolution().status()))
                .filter(declaration -> declaration.resolution().target().filter(target::equals).isPresent())
                .toList();
        if (exact.isEmpty()) {
            throw new SemanticTargetNotFoundException(target);
        }
        if (exact.size() > 1) {
            List<MethodTarget> candidates = exact.stream()
                    .map(declaration -> declaration.resolution().target().orElseThrow())
                    .toList();
            throw new SemanticBindingAmbiguousException(target, candidates);
        }
        ClassMetadata.MethodSignature method = exact.getFirst().method();
        return new SemanticDeclarationAnchor(
                target, new SemanticPosition(method.namePosition().line(), method.namePosition().character()));
    }

    private List<Declaration> declarationsFor(RepositorySyntax syntax, MethodTarget target) {
        return syntax.classes().stream()
                .flatMap(metadata -> metadata.methods().stream()
                        .map(method -> new Declaration(metadata, method, method.analysisTarget())))
                .filter(declaration -> containsSource(declaration.resolution(), target.sourceFile()))
                .filter(declaration -> target.packageName().equals(declaration.metadata().packageName()))
                .filter(declaration -> target.className().equals(declaration.metadata().className()))
                .filter(declaration -> target.methodName().equals(declaration.method().name()))
                .toList();
    }

    private boolean containsSource(MethodTargetResolution resolution, String sourceFile) {
        return resolution.target().stream().anyMatch(target -> sourceFile.equals(target.sourceFile()))
                || resolution.candidates().stream().anyMatch(target -> sourceFile.equals(target.sourceFile()));
    }

    private static int compareParameters(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record Declaration(
            ClassMetadata metadata,
            ClassMetadata.MethodSignature method,
            MethodTargetResolution resolution) {
    }
}
