package com.java.semantic.syntax.application.concept;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** mapper statement logical identity 對完整 canonical 方法目標的 typed 對應結果 */
public record MapperStatementMethodMapping(
        MapperStatementConceptIdentity statementIdentity,
        Status status,
        List<MethodTarget> targets,
        Optional<Reason> reason,
        List<MapperFragmentIdentity> includedFragments) {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, MapperStatementMethodMapping::compareParameters);

    public MapperStatementMethodMapping {
        statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required");
        status = Objects.requireNonNull(status, "status is required");
        targets = Objects.requireNonNull(targets, "targets are required").stream()
                .distinct()
                .sorted(TARGET_ORDER)
                .toList();
        reason = Objects.requireNonNull(reason, "reason is required");
        includedFragments = List.copyOf(Objects.requireNonNull(
                includedFragments, "includedFragments are required"));
        if (status == Status.RESOLVED && targets.size() != 1) {
            throw new IllegalArgumentException("resolved mapper mapping requires one target");
        }
        if (status == Status.AMBIGUOUS && targets.size() < 2) {
            throw new IllegalArgumentException("ambiguous mapper mapping requires multiple targets");
        }
        if (status == Status.UNRESOLVED && !reason.isPresent()) {
            throw new IllegalArgumentException("unresolved mapper mapping requires a reason");
        }
        if (status != Status.UNRESOLVED && reason.isPresent()) {
            throw new IllegalArgumentException("resolved mapper mapping cannot contain a reason");
        }
    }

    /** 依同名宣告數、完整性與已解析目標建立 mapping 狀態 */
    public static MapperStatementMethodMapping fromDeclarations(
            MapperStatementConceptIdentity statementIdentity,
            int declarationCount,
            boolean incompleteMethodResolution,
            List<MethodTarget> targets) {
        if (declarationCount < 0) {
            throw new IllegalArgumentException("declarationCount must not be negative");
        }
        List<MethodTarget> completeTargets = Objects.requireNonNull(targets, "targets are required").stream()
                .distinct()
                .toList();
        if (!incompleteMethodResolution && declarationCount == 1 && completeTargets.size() == 1) {
            return new MapperStatementMethodMapping(
                    statementIdentity,
                    Status.RESOLVED,
                    completeTargets,
                    Optional.empty(),
                    List.of());
        }
        if (!incompleteMethodResolution
                && declarationCount >= 2
                && completeTargets.size() == declarationCount) {
            return new MapperStatementMethodMapping(
                    statementIdentity,
                    Status.AMBIGUOUS,
                    completeTargets,
                    Optional.empty(),
                    List.of());
        }
        return new MapperStatementMethodMapping(
                statementIdentity,
                Status.UNRESOLVED,
                completeTargets,
                Optional.of(Reason.INCOMPLETE_METHOD_RESOLUTION),
                List.of());
    }

    /** 由同一份 production syntax 證據判定 logical mapper statement 的方法對應狀態 */
    public static MapperStatementMethodMapping fromSyntax(
            MapperStatementConceptIdentity identity,
            RepositorySyntax syntax) {
        MapperStatementConceptIdentity statementIdentity = Objects.requireNonNull(identity, "identity is required");
        MapperStatementKey statementKey = statementIdentity.statementKey();
        RepositorySyntax repositorySyntax = Objects.requireNonNull(syntax, "syntax is required");
        List<MethodTargetResolution> declarationResolutions = repositorySyntax.sourceTypes().stream()
                .filter(metadata -> metadata.declaration().identity().fullyQualifiedName()
                        .equals(statementKey.namespace()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> method.name().equals(statementKey.statementId()))
                .map(SourceMethodMetadata::analysisTarget)
                .toList();
        List<MapperFragmentIdentity> includedFragments = repositorySyntax.mapperEvidenceIndex()
                .stream()
                .flatMap(index -> index.statements().stream()
                        .filter(statement -> statement.identity().statementKey().equals(statementKey))
                        .flatMap(statement -> statement.includeRefIds().stream()
                                .flatMap(refId -> index.fragmentIdentitiesForInclude(statement.identity(), refId).stream())))
                .distinct()
                .toList();
        if (!declarationResolutions.isEmpty()) {
            boolean incompleteResolution = declarationResolutions.stream()
                    .anyMatch(resolution -> resolution.status() != AnalysisTargetStatus.RESOLVED);
            List<MethodTarget> completeTargets = declarationResolutions.stream()
                    .flatMap(resolution -> resolution.target().stream())
                    .toList();
            MapperStatementMethodMapping mapping = fromDeclarations(
                    statementIdentity,
                    declarationResolutions.size(),
                    incompleteResolution,
                    completeTargets);
            return new MapperStatementMethodMapping(
                    mapping.statementIdentity(), mapping.status(), mapping.targets(), mapping.reason(), includedFragments);
        }
        List<MethodTarget> evidenceTargets = repositorySyntax.mapperEvidenceIndex().stream()
                .flatMap(index -> index.statements().stream())
                .filter(statement -> statement.identity().statementKey().equals(statementKey))
                .map(MapperStatementEvidence::mappedMethodTarget)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        MapperStatementMethodMapping mapping = fromDeclarations(
                statementIdentity,
                evidenceTargets.size(),
                evidenceTargets.isEmpty(),
                evidenceTargets);
        return new MapperStatementMethodMapping(
                mapping.statementIdentity(), mapping.status(), mapping.targets(), mapping.reason(), includedFragments);
    }

    /** 合併同一 logical mapper statement 的完整方法目標 */
    public MapperStatementMethodMapping merge(MapperStatementMethodMapping other) {
        MapperStatementMethodMapping mapping = Objects.requireNonNull(other, "other is required");
        if (!statementIdentity.equals(mapping.statementIdentity)) {
            throw new IllegalArgumentException("only equal mapper statement identities may merge");
        }
        List<MethodTarget> mergedTargets = Stream.concat(
                        targets.stream(),
                        mapping.targets.stream())
                .toList();
        if (equals(mapping)) {
            return this;
        }
        return new MapperStatementMethodMapping(
                statementIdentity,
                Status.UNRESOLVED,
                mergedTargets,
                Optional.of(Reason.INCOMPLETE_METHOD_RESOLUTION),
                Stream.concat(includedFragments.stream(), mapping.includedFragments.stream())
                        .distinct()
                        .toList());
    }

    /** mapper method mapping 狀態 */
    public enum Status {
        RESOLVED,
        AMBIGUOUS,
        UNRESOLVED
    }

    /** mapper method mapping 無法產生完整目標的 typed 原因 */
    public enum Reason {
        INCOMPLETE_METHOD_RESOLUTION
    }

    private static int compareParameters(List<String> left, List<String> right) {
        int sharedSize = Math.min(left.size(), right.size());
        for (int index = 0; index < sharedSize; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
