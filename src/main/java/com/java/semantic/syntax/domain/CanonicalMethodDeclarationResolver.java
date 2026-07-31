package com.java.semantic.syntax.domain;

import com.java.semantic.identity.MethodTarget;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 從語法抽取結果解析完整方法目標的 canonical 宣告結果 */
public final class CanonicalMethodDeclarationResolver {

    private static final String TARGET_NOT_FOUND = "TARGET_NOT_FOUND";
    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, CanonicalMethodDeclarationResolver::compareParameters);

    /** 依完整的 repository 相對方法目標回傳唯一、未解析或歧義的語法證明 */
    public MethodTargetResolution resolve(RepositorySyntax syntax, MethodTarget target) {
        Objects.requireNonNull(syntax, "syntax is required");
        Objects.requireNonNull(target, "target is required");
        List<Declaration> declarations = declarationsFor(syntax, target);
        List<MethodTargetResolution> ambiguousProofs = declarations.stream()
                .map(Declaration::resolution)
                .filter(resolution -> AnalysisTargetStatus.AMBIGUOUS.equals(resolution.status()))
                .filter(resolution -> resolution.candidates().contains(target))
                .toList();
        List<MethodTarget> ambiguousCandidates = ambiguousProofs.stream()
                .flatMap(resolution -> resolution.candidates().stream())
                .distinct()
                .sorted(TARGET_ORDER)
                .toList();
        if (ambiguousCandidates.size() > 1) {
            String reasonCode = ambiguousProofs.stream()
                    .map(MethodTargetResolution::reasonCode)
                    .findFirst()
                    .orElseThrow();
            return new MethodTargetResolution(
                    AnalysisTargetStatus.AMBIGUOUS,
                    Optional.empty(),
                    ambiguousCandidates,
                    reasonCode);
        }
        List<MethodTarget> exactTargets = declarations.stream()
                .map(Declaration::resolution)
                .filter(resolution -> AnalysisTargetStatus.RESOLVED.equals(resolution.status()))
                .flatMap(resolution -> resolution.target().stream())
                .filter(target::equals)
                .toList();
        if (exactTargets.size() > 1) {
            throw new DuplicateMethodDeclarationProofException(target, exactTargets.size());
        }
        if (exactTargets.size() == 1) {
            return MethodTargetResolution.resolved(exactTargets.getFirst());
        }
        return MethodTargetResolution.unresolved(TARGET_NOT_FOUND);
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
