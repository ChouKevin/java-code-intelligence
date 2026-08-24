package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.MethodTargetDiagnosticId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves direct call sites into normalized local, external, ambiguous, or unresolved outcomes. */
@Slf4j
public final class DirectCallRelationshipResolver {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, DirectCallRelationshipResolver::compareParameters);

    private static final Comparator<DirectCallRelationship> RELATIONSHIP_ORDER = Comparator
            .comparing(DirectCallRelationship::callSite, DirectCallRelationshipResolver::compareRanges)
            .thenComparing(DirectCallRelationship::expression)
            .thenComparing(DirectCallRelationship::strategy);

    private final JavaSemanticService semanticService;
    private final SpringImplementationSelector implementationSelector;
    private final CanonicalTargetProjection canonicalTargetProjection;
    private final ImplementationCandidateFactory candidateFactory;

    public DirectCallRelationshipResolver(
            JavaSemanticService semanticService,
            SpringImplementationSelector implementationSelector,
            CanonicalTargetProjection canonicalTargetProjection) {
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.implementationSelector = Objects.requireNonNull(
                implementationSelector, "implementationSelector is required");
        this.canonicalTargetProjection = Objects.requireNonNull(
                canonicalTargetProjection, "canonicalTargetProjection is required");
        this.candidateFactory = new ImplementationCandidateFactory();
    }

    List<DirectCallRelationship> resolveAll(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget callerTarget,
            SemanticMethod caller) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(index, "index is required");
        Objects.requireNonNull(callerTarget, "callerTarget is required");
        Objects.requireNonNull(caller, "caller is required");

        List<DirectCallRelationship> relationships = new ArrayList<>();
        List<ResolutionFailure> failures = new ArrayList<>();
        Set<SemanticRange> covered = new LinkedHashSet<>();
        for (SemanticCall call : semanticService.outgoingCalls(snapshot, caller)) {
            for (SemanticRange callSite : call.callSites()) {
                covered.add(callSite);
                SyntaxInvocation invocation = hierarchyInvocation(snapshot, index, caller, callSite);
                try {
                    relationships.add(resolveCall(
                            snapshot,
                            index,
                            callerTarget,
                            call,
                            callSite,
                            invocation.expression(),
                            ResolutionStrategy.JDT_CALL_HIERARCHY,
                            invocation));
                } catch (ResolutionFailure exception) {
                    relationships.add(exception.relationship());
                    failures.add(exception);
                }
            }
        }
        for (SyntaxInvocation invocation : invocations(snapshot, index, caller)) {
            SemanticRange callSite = semanticRange(invocation.range());
            if (covered.contains(callSite)) {
                continue;
            }
            try {
                relationships.add(resolveInvocation(
                        snapshot, index, callerTarget, caller, callSite, callSite, invocation));
            } catch (ResolutionFailure exception) {
                relationships.add(exception.relationship());
                failures.add(exception);
            } catch (RuntimeException exception) {
                DirectCallRelationship relationship = DirectCallRelationship.unresolved(
                        callSite,
                        invocation.expression(),
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK);
                relationships.add(relationship);
                failures.add(new ResolutionFailure(exception, relationship));
            }
        }
        List<DirectCallRelationship> orderedRelationships = relationships.stream()
                .sorted(RELATIONSHIP_ORDER)
                .toList();
        if (!CollectionUtils.isEmpty(failures)) {
            throw ResolutionFailure.aggregate(orderedRelationships, failures);
        }
        return orderedRelationships;
    }

    DirectCallRelationship resolveAt(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget callerTarget,
            SemanticMethod caller,
            SemanticRange callSite) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(index, "index is required");
        Objects.requireNonNull(callerTarget, "callerTarget is required");
        Objects.requireNonNull(caller, "caller is required");
        Objects.requireNonNull(callSite, "callSite is required");

        Optional<SyntaxInvocation> invocation = incomingInvocation(
                invocations(snapshot, index, caller), callSite);
        if (!invocation.isPresent()) {
            return DirectCallRelationship.unresolved(
                    callSite, "semantic call", ResolutionStrategy.JDT_DEFINITION_FALLBACK);
        }
        SyntaxInvocation resolvedInvocation = invocation.orElseThrow();
        return resolveInvocation(
                snapshot,
                index,
                callerTarget,
                caller,
                callSite,
                semanticRange(resolvedInvocation.range()),
                resolvedInvocation);
    }

    private Optional<SyntaxInvocation> incomingInvocation(
            List<SyntaxInvocation> invocations,
            SemanticRange callSite) {
        Optional<SyntaxInvocation> exact = invocations.stream()
                .filter(candidate -> semanticRange(candidate.range()).equals(callSite))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return anchorAlignedInvocation(invocations, callSite);
    }

    /**
     * 找出 resolutionAnchor 對齊呼叫位置起點、且範圍涵蓋呼叫位置的唯一語法呼叫證據
     *
     * JDT call hierarchy 回報的是 selection range，語法解析回報的是完整 invocation range
     * 兩者本質上不同，因此在精確範圍比對失敗後，改以 anchor 對齊比對，僅在唯一候選時採用，
     * 避免靜默退化成空白呼叫證據
     */
    private Optional<SyntaxInvocation> anchorAlignedInvocation(
            List<SyntaxInvocation> invocations,
            SemanticRange callSite) {
        List<SyntaxInvocation> anchorAligned = invocations.stream()
                .filter(candidate -> toSemanticPosition(candidate.resolutionAnchor()).equals(callSite.start()))
                .filter(candidate -> contains(candidate.range(), callSite))
                .toList();
        return anchorAligned.size() == 1
                ? Optional.of(anchorAligned.getFirst())
                : Optional.empty();
    }

    private DirectCallRelationship resolveInvocation(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget callerTarget,
            SemanticMethod caller,
            SemanticRange resolutionCallSite,
            SemanticRange relationshipCallSite,
            SyntaxInvocation invocation) {
        SemanticCallResolution resolution = semanticService.resolveCallResolutionAt(
                snapshot,
                caller,
                new SemanticCallSite(resolutionCallSite, toSemanticPosition(invocation.resolutionAnchor())));
        if (SemanticCallResolutionStatus.RESOLVED.equals(resolution.status())) {
            return resolveCall(
                    snapshot,
                    index,
                    callerTarget,
                    resolution.call().orElseThrow(),
                    relationshipCallSite,
                    invocation.expression(),
                    ResolutionStrategy.JDT_DEFINITION_FALLBACK,
                    invocation);
        }
        if (SemanticCallResolutionStatus.AMBIGUOUS.equals(resolution.status())) {
            List<MethodTarget> candidates = resolution.candidates().stream()
                    .map(candidate -> canonicalTarget(snapshot, index, candidate))
                    .flatMap(Optional::stream)
                    .sorted(TARGET_ORDER)
                    .toList();
            if (candidates.size() > 1) {
                return DirectCallRelationship.ambiguous(
                        relationshipCallSite,
                        invocation.expression(),
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK,
                        candidates);
            }
        }
        return DirectCallRelationship.unresolved(
                relationshipCallSite,
                invocation.expression(),
                ResolutionStrategy.JDT_DEFINITION_FALLBACK,
                Optional.of(invocation),
                Optional.empty());
    }

    private DirectCallRelationship resolveCall(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget callerTarget,
            SemanticCall call,
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            SyntaxInvocation invocation) {
        List<String> evidence = List.of(call.rawSignature());
        if (call.external()) {
            return DirectCallRelationship.external(externalSymbol(call, callSite, callerTarget), callSite, expression, evidence);
        }
        if (!call.target().isPresent()) {
            log.debug("phase=callgraph-resolution outcome=unresolved reason=semantic-target-missing callerTargetId={}",
                    MethodTargetDiagnosticId.from(callerTarget));
            return DirectCallRelationship.unresolved(
                    callSite, expression, strategy, Optional.of(invocation), Optional.empty());
        }
        SemanticMethod semanticTarget = call.target().orElseThrow();
        Optional<MethodTarget> target = canonicalTarget(snapshot, index, semanticTarget);
        if (!target.isPresent()) {
            log.debug("phase=callgraph-resolution outcome=unresolved reason=syntax-index-miss callerTargetId={}",
                    MethodTargetDiagnosticId.from(callerTarget));
            return DirectCallRelationship.unresolved(
                    callSite, expression, strategy, Optional.of(invocation), Optional.empty());
        }
        MethodTarget localTarget = target.orElseThrow();
        if (!interfaceDeclaration(index, localTarget)) {
            return DirectCallRelationship.local(
                    localTarget, semanticTarget, callSite, expression, strategy, evidence);
        }
        return selectImplementation(
                snapshot, index, callerTarget, callSite, expression, strategy, semanticTarget, localTarget, invocation, evidence);
    }

    private DirectCallRelationship selectImplementation(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget callerTarget,
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            SemanticMethod declarationMethod,
            MethodTarget declarationTarget,
            SyntaxInvocation invocation,
            List<String> evidence) {
        List<ImplementationCandidate> candidates = new ArrayList<>();
        if (index.method(declarationTarget).map(SourceMethodMetadata::executableDeclaration).orElse(false)) {
            candidates.add(candidate(index, declarationMethod, declarationTarget));
        }
        try {
            for (SemanticMethod implementation : semanticService.implementations(snapshot, declarationMethod).methods()) {
                canonicalTarget(snapshot, index, implementation)
                        .map(target -> candidate(index, implementation, target))
                        .ifPresent(candidates::add);
            }
        } catch (RuntimeException exception) {
            throw new ResolutionFailure(
                    exception,
                    DirectCallRelationship.unresolved(callSite, expression, strategy));
        }
        List<ImplementationCandidate> distinct = candidates.stream()
                .collect(Collectors.toMap(
                        ImplementationCandidate::target,
                        candidate -> candidate,
                        (left, ignored) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(ImplementationCandidate::target, TARGET_ORDER))
                .toList();
        if (CollectionUtils.isEmpty(distinct)) {
            return DirectCallRelationship.unresolved(
                    callSite, expression, strategy,
                    Optional.of(invocation), Optional.of(declarationTarget));
        }
        ImplementationSelection selection = implementationSelector.select(invocation, distinct);
        if (selection instanceof ImplementationSelection.Ambiguous ambiguous) {
            return DirectCallRelationship.ambiguous(
                    callSite, expression, strategy, ambiguous.candidates());
        }
        ImplementationSelection.Selected selected = (ImplementationSelection.Selected) selection;
        return DirectCallRelationship.local(
                selected.candidate().target(),
                selected.candidate().method(),
                callSite,
                expression,
                selected.strategy(),
                evidence);
    }

    private SyntaxInvocation hierarchyInvocation(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller,
            SemanticRange callSite) {
        List<SyntaxInvocation> invocations = invocations(snapshot, index, caller);
        Optional<SyntaxInvocation> exact = invocations.stream()
                .filter(candidate -> semanticRange(candidate.range()).equals(callSite))
                .findFirst();
        if (exact.isPresent()) {
            return exact.orElseThrow();
        }
        return anchorAlignedInvocation(invocations, callSite)
                .orElseGet(() -> new SyntaxInvocation(
                        SyntaxInvocation.InvocationKind.METHOD,
                        syntaxRange(callSite),
                        "semantic call",
                        "",
                        "",
                        "",
                        Optional.empty(),
                        syntaxRange(callSite).start(),
                        List.of()));
    }

    private List<SyntaxInvocation> invocations(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller) {
        return canonicalTarget(snapshot, index, caller)
                .flatMap(index::method)
                .map(SourceMethodMetadata::invocations)
                .orElse(List.of());
    }

    private Optional<MethodTarget> canonicalTarget(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod method) {
        return canonicalTargetProjection.project(semanticService.classifySource(snapshot, method), index, method);
    }

    private boolean interfaceDeclaration(RepositorySyntaxIndex index, MethodTarget target) {
        Optional<SourceTypeMetadata> metadata = index.sourceType(target);
        boolean interfaceDeclaration = metadata
                .map(sourceType -> sourceType.declaration().kind())
                .filter(SourceTypeKind.INTERFACE::equals)
                .isPresent();
        log.debug(
                "phase=callgraph-resolution outcome=interface-classification targetId={} metadataPresent={} interface={}",
                MethodTargetDiagnosticId.from(target), metadata.isPresent(), interfaceDeclaration);
        return interfaceDeclaration;
    }

    private ImplementationCandidate candidate(
            RepositorySyntaxIndex index,
            SemanticMethod method,
            MethodTarget target) {
        return candidateFactory.create(index, method, target)
                .orElseGet(() -> new ImplementationCandidate(method, target, false, List.of(), List.of()));
    }

    private String externalSymbol(SemanticCall call, SemanticRange callSite, MethodTarget callerTarget) {
        return call.target().map(target -> target.packageName() + "." + target.className()
                + "#" + target.methodName() + "(" + String.join(",", target.parameterTypes()) + ")")
                .filter(StringUtils::hasText)
                .or(() -> Optional.of(call.rawSignature()).filter(this::safeExternalSymbol))
                .orElse("external semantic symbol at " + callerTarget.sourceFile() + ":"
                        + callSite.start().line() + ":"
                        + callSite.start().character());
    }

    private boolean safeExternalSymbol(String value) {
        return StringUtils.hasText(value) && value.length() <= 256 && value.chars()
                .allMatch(character -> character >= 32 && character < 127);
    }

    private static SemanticRange semanticRange(SyntaxRange range) {
        return new SemanticRange(toSemanticPosition(range.start()), toSemanticPosition(range.end()));
    }

    private static boolean contains(SyntaxRange range, SemanticRange contained) {
        return comparePositions(range.start(), contained.start()) <= 0
                && comparePositions(range.end(), contained.end()) >= 0;
    }

    private static int comparePositions(SyntaxPosition left, SemanticPosition right) {
        int line = Integer.compare(left.line(), right.line());
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.character(), right.character());
    }

    private static SemanticPosition toSemanticPosition(SyntaxPosition position) {
        return new SemanticPosition(position.line(), position.character());
    }

    private static SyntaxRange syntaxRange(SemanticRange range) {
        return new SyntaxRange(
                new SyntaxPosition(range.start().line(), range.start().character()),
                new SyntaxPosition(range.end().line(), range.end().character()));
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

    private static int compareRanges(SemanticRange left, SemanticRange right) {
        int line = Integer.compare(left.start().line(), right.start().line());
        if (line != 0) {
            return line;
        }
        int character = Integer.compare(left.start().character(), right.start().character());
        if (character != 0) {
            return character;
        }
        line = Integer.compare(left.end().line(), right.end().line());
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.end().character(), right.end().character());
    }

    static final class ResolutionFailure extends RuntimeException {

        private final List<RuntimeException> originals;
        private final List<DirectCallRelationship> relationships;

        private ResolutionFailure(RuntimeException original, DirectCallRelationship relationship) {
            this(List.of(relationship), List.of(original));
        }

        private ResolutionFailure(
                List<DirectCallRelationship> relationships,
                List<RuntimeException> originals) {
            super(firstOriginal(originals));
            this.relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships are required"));
            this.originals = List.copyOf(Objects.requireNonNull(originals, "originals are required"));
        }

        static ResolutionFailure aggregate(
                List<DirectCallRelationship> relationships,
                List<ResolutionFailure> failures) {
            List<RuntimeException> originals = failures.stream()
                    .flatMap(failure -> failure.originals().stream())
                    .toList();
            return new ResolutionFailure(relationships, originals);
        }

        private static RuntimeException firstOriginal(List<RuntimeException> originals) {
            List<RuntimeException> requiredOriginals = List.copyOf(
                    Objects.requireNonNull(originals, "originals are required"));
            if (CollectionUtils.isEmpty(requiredOriginals)) {
                throw new IllegalArgumentException("originals must not be empty");
            }
            return requiredOriginals.getFirst();
        }

        RuntimeException original() {
            return originals.getFirst();
        }

        List<RuntimeException> originals() {
            return originals;
        }

        DirectCallRelationship relationship() {
            return relationships.getFirst();
        }

        List<DirectCallRelationship> relationships() {
            return relationships;
        }
    }
}
