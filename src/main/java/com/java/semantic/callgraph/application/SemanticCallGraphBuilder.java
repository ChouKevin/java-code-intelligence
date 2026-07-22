package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphError;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.MethodTargetDiagnosticId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds one deterministic, response-local outgoing fragment from syntax-proven method targets. */
@Slf4j
public final class SemanticCallGraphBuilder {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, SemanticCallGraphBuilder::compareParameters);

    private static final Comparator<CallObservation> OBSERVATION_ORDER = Comparator
            .comparing(CallObservation::callSite, SemanticCallGraphBuilder::compareRanges)
            .thenComparing(CallObservation::expression)
            .thenComparing(CallObservation::strategy);

    private final JavaSemanticService semanticService;
    private final SpringImplementationSelector implementationSelector;

    public SemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            SpringImplementationSelector implementationSelector) {
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.implementationSelector = Objects.requireNonNull(
                implementationSelector, "implementationSelector is required");
    }

    public OutgoingGraphFragment build(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            MethodTarget rootTarget,
            SemanticMethod root,
            int requestedDepth,
            int depthTwoNodeBudget) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(syntax, "syntax is required");
        Objects.requireNonNull(rootTarget, "rootTarget is required");
        Objects.requireNonNull(root, "root is required");
        Assert.isTrue(requestedDepth == 1 || requestedDepth == 2,
                "requestedDepth must be one or two");
        Assert.isTrue(depthTwoNodeBudget >= 0, "depthTwoNodeBudget must not be negative");

        RepositorySyntaxIndex index = new RepositorySyntaxIndex(snapshot.repositoryId().value(), syntax);
        BuildState state = new BuildState(index, snapshot, requestedDepth, depthTwoNodeBudget);
        state.addRoot(rootTarget);

        List<CallObservation> rootCalls = observations(snapshot, index, root);
        for (CallObservation rootCall : rootCalls) {
            resolveAndAddDepthOne(snapshot, index, state, rootTarget, root, rootCall);
        }
        if (requestedDepth == 2) {
            expandDepthOne(snapshot, index, state);
            state.hydrateDepthTwo();
        }
        return state.fragment();
    }

    private void resolveAndAddDepthOne(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget callerTarget,
            SemanticMethod caller,
            CallObservation observation) {
        List<ResolvedDestination> destinations = destinations(
                snapshot, index, state, callerTarget, caller, observation);
        for (ResolvedDestination destination : destinations) {
            if (destination.externalSymbol().isPresent()) {
                state.addExternalEdge(callerTarget, destination.externalSymbol().orElseThrow(), observation);
            } else {
                MethodTarget target = destination.target().orElseThrow();
                state.addDepthOneEdge(
                        callerTarget,
                        target,
                        observation,
                        destination.strategy(),
                        destination.confidence(),
                        destination.semanticMethod().orElseThrow());
            }
        }
    }

    private void expandDepthOne(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state) {
        List<MethodTarget> depthOneTargets = state.depthOneTargets();
        for (MethodTarget depthOneTarget : depthOneTargets) {
            SemanticMethod depthOneMethod = state.semanticMethod(depthOneTarget).orElseThrow();
            List<CallObservation> calls;
            try {
                calls = observations(snapshot, index, depthOneMethod);
            } catch (RuntimeException exception) {
                state.addChildFailure(depthOneTarget, exception);
                continue;
            }
            for (CallObservation observation : calls) {
                List<ResolvedDestination> destinations;
                try {
                    destinations = destinations(
                            snapshot, index, state, depthOneTarget, depthOneMethod, observation);
                } catch (RuntimeException exception) {
                    state.addUnresolved(depthOneTarget, observation);
                    state.addChildFailure(depthOneTarget, exception);
                    continue;
                }
                for (ResolvedDestination destination : destinations) {
                    if (destination.externalSymbol().isPresent()) {
                        state.addExternalEdge(depthOneTarget, destination.externalSymbol().orElseThrow(), observation);
                    } else {
                        state.addDepthTwoEdge(
                                depthOneTarget,
                                destination.target().orElseThrow(),
                                observation,
                                destination.strategy(),
                                destination.confidence());
                    }
                }
            }
        }
    }

    private List<ResolvedDestination> destinations(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget callerTarget,
            SemanticMethod caller,
            CallObservation observation) {
        if (SemanticCallResolutionStatus.UNRESOLVED.equals(observation.status())) {
            state.addUnresolved(callerTarget, observation);
            return List.of();
        }
        if (SemanticCallResolutionStatus.AMBIGUOUS.equals(observation.status())) {
            state.addAmbiguous(callerTarget, observation);
            return List.of();
        }
        SemanticCall call = observation.call().orElseThrow();
        if (call.external()) {
            return List.of(ResolvedDestination.external(externalSymbol(call, observation, callerTarget)));
        }
        if (call.target().isEmpty()) {
            log.debug("phase=callgraph-resolution outcome=unresolved reason=semantic-target-missing callerTargetId={}",
                    MethodTargetDiagnosticId.from(callerTarget));
            state.addUnresolved(callerTarget, observation);
            return List.of();
        }
        SemanticMethod semanticTarget = call.target().orElseThrow();
        Optional<MethodTarget> target = targetFor(snapshot, index, semanticTarget);
        if (target.isEmpty()) {
            log.debug("phase=callgraph-resolution outcome=unresolved reason=syntax-index-miss callerTargetId={}",
                    MethodTargetDiagnosticId.from(callerTarget));
            state.addUnresolved(callerTarget, observation);
            return List.of();
        }
        if (!interfaceDeclaration(index, target.orElseThrow())) {
            return List.of(ResolvedDestination.local(
                    target.orElseThrow(), observation.strategy(), confidence(observation.strategy()), semanticTarget));
        }
        return selectImplementation(snapshot, index, state, callerTarget, observation, target.orElseThrow(), semanticTarget);
    }

    private List<ResolvedDestination> selectImplementation(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget callerTarget,
            CallObservation observation,
            MethodTarget declarationTarget,
            SemanticMethod declarationMethod) {
        List<ImplementationCandidate> candidates = new ArrayList<>();
        if (index.method(declarationTarget)
                .map(MethodSignature::executableDeclaration)
                .orElse(false)) {
            candidates.add(candidate(index, declarationMethod, declarationTarget));
        }
        try {
            for (SemanticMethod implementation : semanticService.implementations(snapshot, declarationMethod)) {
                targetFor(snapshot, index, implementation)
                        .map(target -> candidate(index, implementation, target))
                        .ifPresent(candidates::add);
            }
        } catch (RuntimeException exception) {
            if (state.isRoot(callerTarget)) {
                throw exception;
            }
            state.addUnresolved(callerTarget, observation);
            state.addChildFailure(callerTarget, exception);
            return List.of();
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
        if (distinct.isEmpty()) {
            state.addUnresolved(callerTarget, observation);
            return List.of();
        }
        ImplementationSelection selection = implementationSelector.select(observation.invocation(), distinct);
        if (selection instanceof ImplementationSelection.Ambiguous ambiguous) {
            state.addAmbiguous(callerTarget, observation.withCandidates(ambiguous.candidates()));
            return List.of();
        }
        ImplementationSelection.Selected selected = (ImplementationSelection.Selected) selection;
        return List.of(ResolvedDestination.local(
                selected.candidate().target(), selected.strategy(), selected.confidence(), selected.candidate().method()));
    }

    private List<CallObservation> observations(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod caller) {
        List<CallObservation> observations = new ArrayList<>();
        Set<SemanticRange> covered = new LinkedHashSet<>();
        for (SemanticCall call : semanticService.outgoingCalls(snapshot, caller)) {
            for (SemanticRange callSite : call.callSites()) {
                covered.add(callSite);
                SyntaxInvocation invocation = invocation(snapshot, index, caller, callSite);
                observations.add(CallObservation.resolved(
                        call,
                        callSite,
                        invocation.expression(),
                        ResolutionStrategy.JDT_CALL_HIERARCHY,
                        invocation));
            }
        }
        for (SyntaxInvocation invocation : invocations(snapshot, index, caller)) {
            SemanticRange range = semanticRange(invocation.range());
            if (covered.contains(range)) {
                continue;
            }
            SemanticCallResolution resolution = semanticService.resolveCallResolutionAt(
                    snapshot,
                    caller,
                    new SemanticCallSite(range, toSemanticPosition(invocation.resolutionAnchor())));
            if (SemanticCallResolutionStatus.RESOLVED.equals(resolution.status())) {
                observations.add(CallObservation.resolved(
                        resolution.call().orElseThrow(),
                        range,
                        invocation.expression(),
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK,
                        invocation));
            } else if (SemanticCallResolutionStatus.AMBIGUOUS.equals(resolution.status())) {
                List<MethodTarget> candidates = resolution.candidates().stream()
                        .map(candidate -> targetFor(snapshot, index, candidate))
                        .flatMap(Optional::stream)
                        .sorted(TARGET_ORDER)
                        .toList();
                if (candidates.size() == resolution.candidates().size()) {
                    observations.add(CallObservation.ambiguous(
                            range, invocation.expression(), invocation, candidates));
                } else {
                    observations.add(CallObservation.unresolved(range, invocation.expression(), invocation));
                }
            } else {
                observations.add(CallObservation.unresolved(range, invocation.expression(), invocation));
            }
        }
        return observations.stream().sorted(OBSERVATION_ORDER).toList();
    }

    private Optional<MethodTarget> targetFor(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod method) {
        Optional<String> sourceFile = sourceFile(snapshot, method.location().uri());
        return sourceFile.flatMap(file -> index.target(
                        file,
                        method.packageName(),
                        method.className(),
                        method.methodName(),
                        method.parameterTypes())
                .or(() -> index.method(file, syntaxRange(method.location().range()))
                        .flatMap(signature -> signature.analysisTarget().target())));
    }

    private Optional<String> sourceFile(RepositorySnapshot snapshot, String uri) {
        try {
            Path source = Path.of(URI.create(uri)).normalize();
            Path root = snapshot.root().toAbsolutePath().normalize();
            if (!source.startsWith(root)) {
                return Optional.empty();
            }
            return Optional.of(root.relativize(source).toString().replace('\\', '/'));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean interfaceDeclaration(RepositorySyntaxIndex index, MethodTarget target) {
        Optional<ClassMetadata> metadata = index.classMetadata(target);
        boolean interfaceDeclaration = metadata
                .map(ClassMetadata::kind)
                .filter(TypeKind.INTERFACE::equals)
                .isPresent();
        log.debug(
                "phase=callgraph-resolution outcome=interface-classification targetId={} metadataPresent={} interface={}",
                MethodTargetDiagnosticId.from(target), metadata.isPresent(), interfaceDeclaration);
        return interfaceDeclaration;
    }

    private ImplementationCandidate candidate(
            RepositorySyntaxIndex index, SemanticMethod method, MethodTarget target) {
        Optional<ClassMetadata> metadata = index.classMetadata(target);
        return new ImplementationCandidate(
                method,
                target,
                metadata.map(ClassMetadata::primary).orElse(false),
                metadata.map(ClassMetadata::beanQualifiers).orElse(List.of()),
                metadata.map(ClassMetadata::profiles).orElse(List.of()));
    }

    private List<SyntaxInvocation> invocations(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod caller) {
        return targetFor(snapshot, index, caller)
                .flatMap(index::method)
                .map(MethodSignature::invocations)
                .orElse(List.of());
    }

    private SyntaxInvocation invocation(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod caller, SemanticRange callSite) {
        return invocations(snapshot, index, caller).stream()
                .filter(candidate -> semanticRange(candidate.range()).equals(callSite))
                .findFirst()
                .orElseGet(() -> new SyntaxInvocation(
                        SyntaxInvocation.InvocationKind.METHOD,
                        syntaxRange(callSite),
                        "semantic call",
                        "",
                        "",
                        "",
                        Optional.empty(),
                        syntaxRange(callSite).start()));
    }

    private String externalSymbol(
            SemanticCall call,
            CallObservation observation,
            MethodTarget callerTarget) {
        return call.target().map(target -> target.packageName() + "." + target.className()
                + "#" + target.methodName() + "(" + String.join(",", target.parameterTypes()) + ")")
                .filter(StringUtils::hasText)
                .or(() -> Optional.of(call.rawSignature()).filter(this::safeExternalSymbol))
                .orElse("external semantic symbol at " + callerTarget.sourceFile() + ":"
                        + observation.callSite().start().line() + ":"
                        + observation.callSite().start().character());
    }

    private boolean safeExternalSymbol(String value) {
        return StringUtils.hasText(value) && value.length() <= 256 && value.chars()
                .allMatch(character -> character >= 32 && character < 127);
    }

    private static SemanticRange semanticRange(SyntaxRange range) {
        return new SemanticRange(
                toSemanticPosition(range.start()),
                toSemanticPosition(range.end()));
    }

    private static SemanticPosition toSemanticPosition(SyntaxPosition position) {
        return new SemanticPosition(position.line(), position.character());
    }

    private static SyntaxRange syntaxRange(SemanticRange range) {
        return new SyntaxRange(
                new SyntaxPosition(range.start().line(), range.start().character()),
                new SyntaxPosition(range.end().line(), range.end().character()));
    }

    private static double confidence(ResolutionStrategy strategy) {
        return ResolutionStrategy.JDT_CALL_HIERARCHY.equals(strategy) ? 1.0d : 0.9d;
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

    private record CallObservation(
            SemanticCallResolutionStatus status,
            Optional<SemanticCall> call,
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            SyntaxInvocation invocation,
            List<MethodTarget> candidates) {

        private CallObservation {
            status = Objects.requireNonNull(status, "status is required");
            call = Objects.requireNonNull(call, "call is required");
            callSite = Objects.requireNonNull(callSite, "callSite is required");
            Assert.hasText(expression, "expression is required");
            strategy = Objects.requireNonNull(strategy, "strategy is required");
            invocation = Objects.requireNonNull(invocation, "invocation is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        }

        static CallObservation resolved(
                SemanticCall call, SemanticRange callSite, String expression,
                ResolutionStrategy strategy, SyntaxInvocation invocation) {
            return new CallObservation(
                    SemanticCallResolutionStatus.RESOLVED,
                    Optional.of(call), callSite, expression, strategy, invocation, List.of());
        }

        static CallObservation unresolved(SemanticRange callSite, String expression, SyntaxInvocation invocation) {
            return new CallObservation(
                    SemanticCallResolutionStatus.UNRESOLVED,
                    Optional.empty(), callSite, expression,
                    ResolutionStrategy.JDT_DEFINITION_FALLBACK, invocation, List.of());
        }

        static CallObservation ambiguous(
                SemanticRange callSite, String expression, SyntaxInvocation invocation, List<MethodTarget> candidates) {
            return new CallObservation(
                    SemanticCallResolutionStatus.AMBIGUOUS,
                    Optional.empty(), callSite, expression,
                    ResolutionStrategy.JDT_DEFINITION_FALLBACK, invocation, candidates);
        }

        CallObservation withCandidates(List<MethodTarget> replacement) {
            return new CallObservation(status, call, callSite, expression, strategy, invocation, replacement);
        }
    }

    private record ResolvedDestination(
            Optional<MethodTarget> target,
            Optional<String> externalSymbol,
            ResolutionStrategy strategy,
            double confidence,
            Optional<SemanticMethod> semanticMethod) {

        static ResolvedDestination local(
                MethodTarget target, ResolutionStrategy strategy, double confidence, SemanticMethod semanticMethod) {
            return new ResolvedDestination(
                    Optional.of(target), Optional.empty(), strategy, confidence, Optional.of(semanticMethod));
        }

        static ResolvedDestination external(String externalSymbol) {
            return new ResolvedDestination(
                    Optional.empty(), Optional.of(externalSymbol), ResolutionStrategy.EXTERNAL_LIBRARY, 1.0d, Optional.empty());
        }
    }

    private static final class BuildState {

        private static final Comparator<GraphEdge> EDGE_ORDER = Comparator
                .comparing(GraphEdge::callerNodeId, Comparator.comparing(CallNodeId::value))
                .thenComparing(GraphEdge::callSite, Comparator
                        .comparing(CallSiteRange::sourceFile)
                        .thenComparingInt(CallSiteRange::startLine)
                        .thenComparingInt(CallSiteRange::startCharacter)
                        .thenComparingInt(CallSiteRange::endLine)
                        .thenComparingInt(CallSiteRange::endCharacter))
                .thenComparing(GraphEdge::calleeNodeId, Comparator.comparing(CallNodeId::value));

        private final RepositorySyntaxIndex index;
        private final RepositorySnapshot snapshot;
        private final int requestedDepth;
        private final int budget;
        private MethodTarget rootTarget;
        private final CallTraversalState traversalState = new CallTraversalState();
        private final Map<MethodTarget, LocalNode> localNodes = new LinkedHashMap<>();
        private final Map<MethodTarget, SemanticMethod> semanticMethods = new LinkedHashMap<>();
        private final Map<String, CallNodeId> externalNodes = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<GraphWarning> warnings = new ArrayList<>();
        private final List<GraphError> errors = new ArrayList<>();
        private final List<PendingDepthTwoEdge> pendingDepthTwo = new ArrayList<>();
        private final Set<EdgeKey> edgeKeys = new LinkedHashSet<>();

        private BuildState(RepositorySyntaxIndex index, RepositorySnapshot snapshot, int requestedDepth, int budget) {
            this.index = index;
            this.snapshot = snapshot;
            this.requestedDepth = requestedDepth;
            this.budget = budget;
        }

        void addRoot(MethodTarget target) {
            rootTarget = target;
            localNodes.put(target, LocalNode.full(traversalState.localNodeId(target), target, 0, requestedDepth));
        }

        boolean isRoot(MethodTarget target) {
            return target.equals(rootTarget);
        }

        void addDepthOneEdge(
                MethodTarget caller,
                MethodTarget target,
                CallObservation observation,
                ResolutionStrategy strategy,
                double confidence,
                SemanticMethod semanticMethod) {
            LocalNode callee = localNodes.computeIfAbsent(target,
                    ignored -> LocalNode.full(traversalState.localNodeId(target), target, 1, requestedDepth));
            semanticMethods.putIfAbsent(target, semanticMethod);
            addEdge(caller, callee.nodeId(), observation, strategy, confidence);
        }

        void addDepthTwoEdge(
                MethodTarget caller,
                MethodTarget target,
                CallObservation observation,
                ResolutionStrategy strategy,
                double confidence) {
            pendingDepthTwo.add(new PendingDepthTwoEdge(caller, target, observation, strategy, confidence));
        }

        void hydrateDepthTwo() {
            List<MethodTarget> orderedTargets = pendingDepthTwo.stream()
                    .map(PendingDepthTwoEdge::target)
                    .distinct()
                    .sorted(TARGET_ORDER)
                    .toList();
            int expanded = 0;
            for (MethodTarget target : orderedTargets) {
                LocalNode existing = localNodes.get(target);
                if (existing != null) { // cs-allow
                    continue;
                }
                CallNodeId nodeId = traversalState.localNodeId(target);
                if (expanded < budget) {
                    localNodes.put(target, LocalNode.full(nodeId, target, 2, requestedDepth));
                    expanded++;
                } else {
                    localNodes.put(target, LocalNode.targetOnly(nodeId, target));
                }
            }
            for (PendingDepthTwoEdge pending : pendingDepthTwo) {
                LocalNode target = localNodes.get(pending.target());
                addEdge(pending.caller(), target.nodeId(), pending.observation(), pending.strategy(), pending.confidence());
            }
        }

        void addExternalEdge(MethodTarget caller, String symbol, CallObservation observation) {
            CallNodeId nodeId = externalNodes.computeIfAbsent(symbol, traversalState::externalNodeId);
            addEdge(caller, nodeId, observation, ResolutionStrategy.EXTERNAL_LIBRARY, 1.0d);
        }

        void addUnresolved(MethodTarget caller, CallObservation observation) {
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_UNRESOLVED",
                    "descendant call target is not proven",
                    localNodes.get(caller).nodeId(),
                    Optional.of(observation.expression()),
                    Optional.of(callSite(caller, observation.callSite())),
                    List.of()));
        }

        void addAmbiguous(MethodTarget caller, CallObservation observation) {
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_AMBIGUOUS",
                    "descendant call has multiple exact targets",
                    localNodes.get(caller).nodeId(),
                    Optional.of(observation.expression()),
                    Optional.of(callSite(caller, observation.callSite())),
                    observation.candidates().stream().sorted(TARGET_ORDER).toList()));
        }

        void addChildFailure(MethodTarget caller, RuntimeException exception) {
            errors.add(new GraphError(
                    "CHILD_SEMANTIC_QUERY_FAILED",
                    "descendant semantic query failed: " + exception.getClass().getSimpleName(),
                    localNodes.get(caller).nodeId()));
        }

        List<MethodTarget> depthOneTargets() {
            return localNodes.values().stream()
                    .filter(node -> node.depth() == 1)
                    .map(LocalNode::target)
                    .sorted(TARGET_ORDER)
                    .toList();
        }

        Optional<SemanticMethod> semanticMethod(MethodTarget target) {
            return Optional.ofNullable(semanticMethods.get(target));
        }

        OutgoingGraphFragment fragment() {
            List<GraphNode> nodes = nodes();
            List<GraphWarning> normalizedWarnings = warnings.stream()
                    .sorted(Comparator.comparing(GraphWarning::code)
                            .thenComparing(GraphWarning::nodeId, Comparator.comparing(CallNodeId::value))
                            .thenComparing(warning -> warning.callSite().map(CallSiteRange::sourceFile).orElse("")))
                    .toList();
            List<GraphError> normalizedErrors = errors.stream()
                    .sorted(Comparator.comparing(GraphError::code)
                            .thenComparing(GraphError::nodeId, Comparator.comparing(CallNodeId::value)))
                    .toList();
            boolean budgetReached = localNodes.values().stream()
                    .anyMatch(node -> NodeTraversalState.BUDGET_CUTOFF.equals(node.traversalState()));
            if (budgetReached) {
                CallNodeId root = localNodes.values().stream()
                        .filter(node -> node.depth() == 0)
                        .findFirst()
                        .orElseThrow()
                        .nodeId();
                normalizedWarnings = appendBudgetWarning(normalizedWarnings, root);
            }
            GraphAnalysisStatus status = normalizedWarnings.isEmpty() && normalizedErrors.isEmpty()
                    ? GraphAnalysisStatus.SUCCESS
                    : GraphAnalysisStatus.PARTIAL;
            GraphTraversal traversal = new GraphTraversal(
                    requestedDepth,
                    expandedNodeCount(),
                    budget,
                    true,
                    budgetReached ? GraphLimitReason.NODE_BUDGET : GraphLimitReason.NONE);
            CallNodeId root = localNodes.values().stream()
                    .filter(node -> node.depth() == 0)
                    .findFirst()
                    .orElseThrow()
                    .nodeId();
            return new OutgoingGraphFragment(
                    status,
                    snapshot.revision(),
                    root,
                    traversal,
                    nodes,
                    edges.stream().sorted(EDGE_ORDER).toList(),
                    normalizedWarnings,
                    normalizedErrors);
        }

        private List<GraphWarning> appendBudgetWarning(List<GraphWarning> existing, CallNodeId root) {
            List<GraphWarning> all = new ArrayList<>(existing);
            all.add(new GraphWarning(
                    "NODE_BUDGET_REACHED",
                    "depth-two local source hydration reached the server node budget",
                    root,
                    Optional.empty(),
                    Optional.empty(),
                    List.of()));
            return all.stream()
                    .sorted(Comparator.comparing(GraphWarning::code)
                            .thenComparing(GraphWarning::nodeId, Comparator.comparing(CallNodeId::value)))
                    .toList();
        }

        private int expandedNodeCount() {
            return (int) localNodes.values().stream()
                    .filter(node -> node.depth() == 2)
                    .filter(node -> NodeTraversalState.DEPTH_BOUNDARY.equals(node.traversalState()))
                    .count();
        }

        private List<GraphNode> nodes() {
            List<GraphNode> nodes = new ArrayList<>();
            for (LocalNode node : localNodes.values()) {
                nodes.add(node.graphNode(index));
            }
            for (Map.Entry<String, CallNodeId> external : externalNodes.entrySet()) {
                nodes.add(new GraphNode(
                        external.getValue(),
                        Optional.empty(),
                        external.getKey(),
                        NodeContentState.EXTERNAL,
                        NodeTraversalState.EXTERNAL,
                        Optional.empty(),
                        Optional.empty()));
            }
            return nodes.stream().sorted(Comparator
                    .comparing((GraphNode node) -> node.target().map(MethodTarget::sourceFile).orElse("~"))
                    .thenComparing(node -> node.target().map(MethodTarget::packageName).orElse(""))
                    .thenComparing(node -> node.target().map(MethodTarget::className).orElse(""))
                    .thenComparing(node -> node.target().map(MethodTarget::methodName).orElse(""))
                    .thenComparing(node -> node.nodeId().value()))
                    .toList();
        }

        private void addEdge(
                MethodTarget caller,
                CallNodeId callee,
                CallObservation observation,
                ResolutionStrategy strategy,
                double confidence) {
            CallNodeId callerId = localNodes.get(caller).nodeId();
            CallSiteRange range = callSite(caller, observation.callSite());
            EdgeKey key = new EdgeKey(callerId, callee, range);
            if (!edgeKeys.add(key)) {
                return;
            }
            List<String> evidence = observation.call().map(SemanticCall::rawSignature)
                    .map(List::of)
                    .orElse(List.of());
            edges.add(new GraphEdge(
                    callerId, callee, range, observation.expression(), strategy, confidence, evidence));
        }

        private CallSiteRange callSite(MethodTarget caller, SemanticRange range) {
            return new CallSiteRange(
                    caller.sourceFile(),
                    range.start().line(),
                    range.start().character(),
                    range.end().line(),
                    range.end().character());
        }

        private record EdgeKey(CallNodeId caller, CallNodeId callee, CallSiteRange callSite) {
        }

        private record PendingDepthTwoEdge(
                MethodTarget caller,
                MethodTarget target,
                CallObservation observation,
                ResolutionStrategy strategy,
                double confidence) {
        }

        private record LocalNode(
                CallNodeId nodeId,
                MethodTarget target,
                int depth,
                NodeContentState contentState,
                NodeTraversalState traversalState) {

            static LocalNode full(CallNodeId nodeId, MethodTarget target, int depth, int requestedDepth) {
                NodeTraversalState traversalState = depth < requestedDepth
                        ? NodeTraversalState.EXPANDED
                        : NodeTraversalState.DEPTH_BOUNDARY;
                return new LocalNode(nodeId, target, depth, NodeContentState.FULL_SOURCE, traversalState);
            }

            static LocalNode targetOnly(CallNodeId nodeId, MethodTarget target) {
                return new LocalNode(nodeId, target, 2, NodeContentState.TARGET_ONLY,
                        NodeTraversalState.BUDGET_CUTOFF);
            }

            GraphNode graphNode(RepositorySyntaxIndex index) {
                if (NodeContentState.TARGET_ONLY.equals(contentState)) {
                    return new GraphNode(
                            nodeId, Optional.of(target), "", contentState, traversalState,
                            Optional.empty(), Optional.empty());
                }
                MethodSignature method = index.method(target).orElseThrow();
                CallSiteRange range = new CallSiteRange(
                        target.sourceFile(),
                        method.range().start().line(),
                        method.range().start().character(),
                        method.range().end().line(),
                        method.range().end().character());
                return new GraphNode(
                        nodeId,
                        Optional.of(target),
                        "",
                        contentState,
                        traversalState,
                        Optional.of(method.source().text()),
                        Optional.of(range));
            }
        }
    }
}
