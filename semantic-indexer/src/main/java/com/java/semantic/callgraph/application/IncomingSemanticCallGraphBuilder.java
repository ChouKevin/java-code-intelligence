package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphError;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.MethodTargetDiagnosticId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticIncomingCall;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Builds one deterministic, response-local incoming fragment from forward-validated call sites. */
@Slf4j
public final class IncomingSemanticCallGraphBuilder {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, IncomingSemanticCallGraphBuilder::compareParameters);

    private static final Comparator<SemanticRange> RANGE_ORDER = Comparator
            .comparing(SemanticRange::start, Comparator
                    .comparingInt(SemanticPosition::line)
                    .thenComparingInt(SemanticPosition::character))
            .thenComparing(SemanticRange::end, Comparator
                    .comparingInt(SemanticPosition::line)
                    .thenComparingInt(SemanticPosition::character));

    private static final Set<ResolutionStrategy> OPAQUE_DATA_ACCESS_RELABELING_STRATEGIES = Set.of(
            ResolutionStrategy.MYBATIS_MAPPER, ResolutionStrategy.SPRING_DATA_REPOSITORY);

    private final JavaSemanticService semanticService;
    private final DirectCallRelationshipResolver relationshipResolver;
    private final CanonicalTargetProjection canonicalTargetProjection;
    private final DataAccessEvidence dataAccessEvidence;

    public IncomingSemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            DirectCallRelationshipResolver relationshipResolver) {
        this(semanticService, relationshipResolver, new CanonicalTargetProjection(), new DataAccessEvidence());
    }

    public IncomingSemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            DirectCallRelationshipResolver relationshipResolver,
            DataAccessEvidence dataAccessEvidence) {
        this(semanticService, relationshipResolver, new CanonicalTargetProjection(), dataAccessEvidence);
    }

    public IncomingSemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            DirectCallRelationshipResolver relationshipResolver,
            CanonicalTargetProjection canonicalTargetProjection,
            DataAccessEvidence dataAccessEvidence) {
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.relationshipResolver = Objects.requireNonNull(relationshipResolver, "relationshipResolver is required");
        this.canonicalTargetProjection = Objects.requireNonNull(
                canonicalTargetProjection, "canonicalTargetProjection is required");
        this.dataAccessEvidence = Objects.requireNonNull(dataAccessEvidence, "dataAccessEvidence is required");
    }

    public IncomingGraphFragment build(
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
        Assert.isTrue(requestedDepth == 1 || requestedDepth == 2, "requestedDepth must be one or two");
        Assert.isTrue(depthTwoNodeBudget >= 0, "depthTwoNodeBudget must not be negative");

        RepositorySyntaxIndex index = new RepositorySyntaxIndex(snapshot.repositoryId().value(), syntax);
        BuildState state = new BuildState(index, snapshot, requestedDepth, depthTwoNodeBudget);
        state.addRoot(rootTarget, root);
        discoverRoot(snapshot, index, state, rootTarget, root);
        if (requestedDepth == 2) {
            expandDepthOne(snapshot, index, state);
            state.hydrateDepthTwo();
        }
        return state.fragment();
    }

    private void discoverRoot(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget rootTarget,
            SemanticMethod root) {
        SemanticIncomingCallResult result = semanticService.incomingCalls(snapshot, root);
        if (CollectionUtils.isEmpty(result.calls()) && !CollectionUtils.isEmpty(result.issues())) {
            log.warn("phase=incoming-callgraph outcome=root-rejected repositoryId={} targetId={} rejectedCount={} depth={}",
                    snapshot.repositoryId().value(), MethodTargetDiagnosticId.from(rootTarget), result.issues().size(),
                    state.requestedDepth());
            throw new SemanticProtocolException();
        }
        resolveNeighborhood(snapshot, index, state, rootTarget, result.calls(), result.issues().size(), true);
    }

    private void expandDepthOne(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state) {
        for (MethodTarget calleeTarget : state.depthOneTargets()) {
            SemanticMethod callee = state.semanticMethod(calleeTarget).orElseThrow();
            SemanticIncomingCallResult result;
            try {
                result = semanticService.incomingCalls(snapshot, callee);
            } catch (RuntimeException exception) {
                state.addChildFailure(calleeTarget, exception);
                log.warn("phase=incoming-callgraph outcome=child-query-failed repositoryId={} targetId={} depth={} exceptionClass={}",
                        snapshot.repositoryId().value(), MethodTargetDiagnosticId.from(calleeTarget), state.requestedDepth(),
                        exception.getClass().getSimpleName());
                continue;
            }
            resolveNeighborhood(snapshot, index, state, calleeTarget, result.calls(), result.issues().size(), false);
        }
    }

    private void resolveNeighborhood(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget calleeTarget,
            List<SemanticIncomingCall> calls,
            int issueCount,
            boolean rootNeighborhood) {
        CanonicalCallers canonicalCallers = canonicalCallers(snapshot, index, calls);
        int rejectedCount = issueCount + canonicalCallers.rejectedCount();
        state.addIncomingRejection(calleeTarget, rejectedCount);
        if (rootNeighborhood && CollectionUtils.isEmpty(canonicalCallers.callers()) && rejectedCount > 0) {
            log.warn("phase=incoming-callgraph outcome=root-rejected repositoryId={} targetId={} rejectedCount={} depth={}",
                    snapshot.repositoryId().value(), MethodTargetDiagnosticId.from(calleeTarget), rejectedCount,
                    state.requestedDepth());
            throw new SemanticProtocolException();
        }
        for (IncomingCaller incoming : canonicalCallers.callers()) {
            List<DirectCallRelationship> relationships = relabelOpaqueDataAccessCallers(
                    index, state, calleeTarget,
                    resolveRelationships(snapshot, index, state, calleeTarget, incoming, rootNeighborhood));
            boolean exactRelationship = relationships.stream()
                    .anyMatch(relationship -> isExactLocalRelationship(relationship, calleeTarget));
            if (!exactRelationship) {
                for (DirectCallRelationship relationship : relationships) {
                    state.addRejectedRelationship(calleeTarget, incoming.target(), relationship);
                }
                continue;
            }
            if (rootNeighborhood) {
                state.addDepthOneCaller(incoming.target(), incoming.caller());
            }
            for (DirectCallRelationship relationship : relationships) {
                if (rootNeighborhood) {
                    state.addDepthOneRelationship(incoming.target(), calleeTarget, relationship);
                } else {
                    state.stageDepthTwoRelationship(incoming.target(), calleeTarget, relationship);
                }
            }
        }
    }

    private List<DirectCallRelationship> resolveRelationships(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget calleeTarget,
            IncomingCaller incoming,
            boolean rootNeighborhood) {
        List<DirectCallRelationship> relationships = new ArrayList<>();
        for (SemanticRange callSite : incoming.callSites()) {
            try {
                relationships.add(relationshipResolver.resolveAt(
                        snapshot, index, incoming.target(), incoming.caller(), callSite));
            } catch (DirectCallRelationshipResolver.ResolutionFailure exception) {
                RuntimeException original = exception.original();
                if (rootNeighborhood) {
                    throw original;
                }
                state.addChildFailure(calleeTarget, original);
                log.warn("phase=incoming-callgraph outcome=forward-validation-failed repositoryId={} targetId={} depth={} exceptionClass={}",
                        snapshot.repositoryId().value(), MethodTargetDiagnosticId.from(calleeTarget), state.requestedDepth(),
                        original.getClass().getSimpleName());
            } catch (RuntimeException exception) {
                if (rootNeighborhood) {
                    throw exception;
                }
                state.addChildFailure(calleeTarget, exception);
                log.warn("phase=incoming-callgraph outcome=forward-validation-failed repositoryId={} targetId={} depth={} exceptionClass={}",
                        snapshot.repositoryId().value(), MethodTargetDiagnosticId.from(calleeTarget), state.requestedDepth(),
                        exception.getClass().getSimpleName());
            }
        }
        return relationships;
    }

    private static boolean isExactLocalRelationship(DirectCallRelationship relationship, MethodTarget calleeTarget) {
        return DirectCallRelationship.Status.LOCAL.equals(relationship.status())
                && relationship.target().filter(calleeTarget::equals).isPresent();
    }

    /**
     * 將指向資料存取介面（具名不透明資料存取策略）的 UNRESOLVED 前向關係，重標為指向該介面方法的 LOCAL 邊
     * <p>
     * 讓 incoming 與 outgoing 一致地依證據接受不透明資料存取呼叫者；僅
     * {@code MYBATIS_MAPPER} 與 {@code SPRING_DATA_REPOSITORY} 的具名策略證據會被接受，
     * {@code DATA_ACCESS_WITHOUT_EVIDENCE} 或無證據維持原樣，交由既有 fail-closed 拒絕路徑
     */
    private List<DirectCallRelationship> relabelOpaqueDataAccessCallers(
            RepositorySyntaxIndex index,
            BuildState state,
            MethodTarget calleeTarget,
            List<DirectCallRelationship> relationships) {
        Optional<EvidenceMatch> match = opaqueDataAccessRelabelingEvidence(index, calleeTarget);
        Optional<SemanticMethod> calleeMethod = state.semanticMethod(calleeTarget);
        if (match.isEmpty() || calleeMethod.isEmpty()) {
            return relationships;
        }
        EvidenceMatch evidenceMatch = match.orElseThrow();
        SemanticMethod semanticMethod = calleeMethod.orElseThrow();
        List<DirectCallRelationship> relabeled = new ArrayList<>();
        for (DirectCallRelationship relationship : relationships) {
            if (isOpaqueDataAccessCall(relationship, calleeTarget)) {
                relabeled.add(DirectCallRelationship.local(
                        calleeTarget, semanticMethod, relationship.callSite(), relationship.expression(),
                        evidenceMatch.strategy(), evidenceMatch.evidence()));
            } else {
                relabeled.add(relationship);
            }
        }
        return relabeled;
    }

    private static boolean isOpaqueDataAccessCall(DirectCallRelationship relationship, MethodTarget calleeTarget) {
        return DirectCallRelationship.Status.UNRESOLVED.equals(relationship.status())
                && relationship.declarationTarget().filter(calleeTarget::equals).isPresent();
    }

    private Optional<EvidenceMatch> opaqueDataAccessRelabelingEvidence(
            RepositorySyntaxIndex index, MethodTarget calleeTarget) {
        Optional<SourceTypeMetadata> declaringType = index.sourceType(calleeTarget);
        Optional<SourceMethodMetadata> declaredMethod = index.method(calleeTarget);
        if (declaringType.isEmpty() || declaredMethod.isEmpty()) {
            return Optional.empty();
        }
        return dataAccessEvidence.evaluate(declaringType.orElseThrow(), declaredMethod.orElseThrow(), calleeTarget)
                .filter(match -> OPAQUE_DATA_ACCESS_RELABELING_STRATEGIES.contains(match.strategy()));
    }

    private CanonicalCallers canonicalCallers(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            List<SemanticIncomingCall> calls) {
        Map<MethodTarget, IncomingCaller> callers = new LinkedHashMap<>();
        int rejectedCount = 0;
        for (SemanticIncomingCall call : calls) {
            Optional<MethodTarget> target = canonicalTargetProjection.project(
                    semanticService.classifySource(snapshot, call.caller()), index, call.caller());
            if (target.isPresent()) {
                MethodTarget callerTarget = target.orElseThrow();
                callers.merge(
                        callerTarget,
                        new IncomingCaller(
                                callerTarget,
                                call.caller(),
                                call.callSites().stream().distinct().sorted(RANGE_ORDER).toList()),
                        IncomingSemanticCallGraphBuilder::mergeIncomingCallers);
            } else {
                rejectedCount++;
            }
        }
        return new CanonicalCallers(callers.values().stream()
                .sorted(Comparator.comparing(IncomingCaller::target, TARGET_ORDER)
                        .thenComparing(IncomingCaller::caller, IncomingSemanticCallGraphBuilder::compareSemanticMethods))
                .toList(), rejectedCount);
    }

    private static IncomingCaller mergeIncomingCallers(IncomingCaller left, IncomingCaller right) {
        List<SemanticRange> callSites = new ArrayList<>(left.callSites());
        callSites.addAll(right.callSites());
        SemanticMethod caller = compareSemanticMethods(left.caller(), right.caller()) <= 0
                ? left.caller()
                : right.caller();
        return new IncomingCaller(left.target(), caller, callSites.stream().distinct().sorted(RANGE_ORDER).toList());
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

    private static int compareSemanticMethods(SemanticMethod left, SemanticMethod right) {
        int packageName = left.packageName().compareTo(right.packageName());
        if (packageName != 0) {
            return packageName;
        }
        int className = left.className().compareTo(right.className());
        if (className != 0) {
            return className;
        }
        int methodName = left.methodName().compareTo(right.methodName());
        if (methodName != 0) {
            return methodName;
        }
        int parameters = compareParameters(left.parameterTypes(), right.parameterTypes());
        if (parameters != 0) {
            return parameters;
        }
        int returnType = left.returnType().compareTo(right.returnType());
        if (returnType != 0) {
            return returnType;
        }
        int locationUri = left.location().uri().compareTo(right.location().uri());
        if (locationUri != 0) {
            return locationUri;
        }
        int range = RANGE_ORDER.compare(left.location().range(), right.location().range());
        if (range != 0) {
            return range;
        }
        return RANGE_ORDER.compare(left.location().selectionRange(), right.location().selectionRange());
    }

    private record IncomingCaller(MethodTarget target, SemanticMethod caller, List<SemanticRange> callSites) {
    }

    private record CanonicalCallers(List<IncomingCaller> callers, int rejectedCount) {
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
        private final CallTraversalState traversalState = new CallTraversalState();
        private final Map<MethodTarget, LocalNode> localNodes = new LinkedHashMap<>();
        private final Map<MethodTarget, SemanticMethod> semanticMethods = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<GraphWarning> warnings = new ArrayList<>();
        private final List<GraphError> errors = new ArrayList<>();
        private final List<PendingDepthTwoRelationship> pendingDepthTwo = new ArrayList<>();
        private final Set<EdgeKey> edgeKeys = new LinkedHashSet<>();

        private BuildState(RepositorySyntaxIndex index, RepositorySnapshot snapshot, int requestedDepth, int budget) {
            this.index = index;
            this.snapshot = snapshot;
            this.requestedDepth = requestedDepth;
            this.budget = budget;
        }

        int requestedDepth() {
            return requestedDepth;
        }

        void addRoot(MethodTarget target, SemanticMethod root) {
            localNodes.put(target, LocalNode.full(traversalState.localNodeId(target), target, 0, requestedDepth));
            semanticMethods.put(target, root);
        }

        void addDepthOneCaller(MethodTarget target, SemanticMethod semanticMethod) {
            localNodes.computeIfAbsent(target,
                    ignored -> LocalNode.full(traversalState.localNodeId(target), target, 1, requestedDepth));
            semanticMethods.putIfAbsent(target, semanticMethod);
        }

        void addDepthOneRelationship(
                MethodTarget caller,
                MethodTarget callee,
                DirectCallRelationship relationship) {
            applyRelationship(caller, callee, relationship);
        }

        void stageDepthTwoRelationship(
                MethodTarget caller,
                MethodTarget callee,
                DirectCallRelationship relationship) {
            pendingDepthTwo.add(new PendingDepthTwoRelationship(caller, callee, relationship));
        }

        void addRejectedRelationship(
                MethodTarget callee,
                MethodTarget caller,
                DirectCallRelationship relationship) {
            if (DirectCallRelationship.Status.AMBIGUOUS.equals(relationship.status())) {
                addAmbiguous(callee, caller, relationship);
            } else {
                addUnresolved(callee, caller, relationship);
            }
        }

        void hydrateDepthTwo() {
            List<MethodTarget> orderedTargets = pendingDepthTwo.stream()
                    .map(PendingDepthTwoRelationship::caller)
                    .distinct()
                    .sorted(TARGET_ORDER)
                    .toList();
            int expanded = 0;
            for (MethodTarget target : orderedTargets) {
                Optional<LocalNode> existing = Optional.ofNullable(localNodes.get(target));
                if (existing.isPresent()) {
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
            for (PendingDepthTwoRelationship pending : pendingDepthTwo) {
                applyRelationship(pending.caller(), pending.callee(), pending.relationship());
            }
        }

        void addIncomingRejection(MethodTarget callee, int rejectedCount) {
            if (rejectedCount <= 0) {
                return;
            }
            warnings.add(new GraphWarning(
                    "INCOMING_CALLER_REJECTED",
                    "incoming caller rejection count: " + rejectedCount,
                    localNodes.get(callee).nodeId(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of()));
        }

        void addChildFailure(MethodTarget callee, RuntimeException exception) {
            errors.add(new GraphError(
                    "CHILD_SEMANTIC_QUERY_FAILED",
                    "descendant semantic query failed: " + exception.getClass().getSimpleName(),
                    localNodes.get(callee).nodeId()));
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

        IncomingGraphFragment fragment() {
            List<GraphWarning> normalizedWarnings = normalizeWarnings(warnings);
            List<GraphError> normalizedErrors = errors.stream()
                    .sorted(Comparator.comparing(GraphError::code)
                            .thenComparing(GraphError::nodeId, Comparator.comparing(CallNodeId::value)))
                    .toList();
            boolean budgetReached = localNodes.values().stream()
                    .anyMatch(node -> NodeTraversalState.BUDGET_CUTOFF.equals(node.traversalState()));
            CallNodeId root = rootNodeId();
            if (budgetReached) {
                List<GraphWarning> withBudgetWarning = new ArrayList<>(normalizedWarnings);
                withBudgetWarning.add(new GraphWarning(
                        "NODE_BUDGET_REACHED",
                        "depth-two local source hydration reached the server node budget",
                        root,
                        Optional.empty(),
                        Optional.empty(),
                        List.of()));
                normalizedWarnings = normalizeWarnings(withBudgetWarning);
            }
            GraphAnalysisStatus status = CollectionUtils.isEmpty(normalizedWarnings)
                    && CollectionUtils.isEmpty(normalizedErrors)
                    ? GraphAnalysisStatus.SUCCESS
                    : GraphAnalysisStatus.PARTIAL;
            GraphTraversal traversal = new GraphTraversal(
                    requestedDepth,
                    expandedNodeCount(),
                    budget,
                    true,
                    budgetReached ? GraphLimitReason.NODE_BUDGET : GraphLimitReason.NONE);
            return new IncomingGraphFragment(
                    status,
                    snapshot.revision(),
                    root,
                    traversal,
                    nodes(),
                    edges.stream().sorted(EDGE_ORDER).toList(),
                    normalizedWarnings,
                    normalizedErrors);
        }

        private void applyRelationship(
                MethodTarget caller,
                MethodTarget callee,
                DirectCallRelationship relationship) {
            if (DirectCallRelationship.Status.LOCAL.equals(relationship.status())
                    && relationship.target().filter(callee::equals).isPresent()) {
                addEdge(caller, callee, relationship);
            } else if (DirectCallRelationship.Status.AMBIGUOUS.equals(relationship.status())) {
                addAmbiguous(caller, caller, relationship);
            } else {
                addUnresolved(caller, caller, relationship);
            }
        }

        private void addUnresolved(
                MethodTarget anchor,
                MethodTarget caller,
                DirectCallRelationship relationship) {
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_UNRESOLVED",
                    "descendant call target is not proven",
                    localNodes.get(anchor).nodeId(),
                    Optional.of(relationship.expression()),
                    Optional.of(callSite(caller, relationship.callSite())),
                    List.of()));
        }

        private void addAmbiguous(
                MethodTarget anchor,
                MethodTarget caller,
                DirectCallRelationship relationship) {
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_AMBIGUOUS",
                    "descendant call has multiple exact targets",
                    localNodes.get(anchor).nodeId(),
                    Optional.of(relationship.expression()),
                    Optional.of(callSite(caller, relationship.callSite())),
                    relationship.candidates().stream().sorted(TARGET_ORDER).toList()));
        }

        private List<GraphWarning> normalizeWarnings(List<GraphWarning> source) {
            return source.stream()
                    .distinct()
                    .sorted(Comparator.comparing(GraphWarning::code)
                            .thenComparing(GraphWarning::nodeId, Comparator.comparing(CallNodeId::value))
                            .thenComparing(warning -> warning.callSite().map(CallSiteRange::sourceFile).orElse(""))
                            .thenComparing(warning -> warning.callSite().map(CallSiteRange::startLine).orElse(-1))
                            .thenComparing(warning -> warning.callSite().map(CallSiteRange::startCharacter).orElse(-1)))
                    .toList();
        }

        private int expandedNodeCount() {
            return (int) localNodes.values().stream()
                    .filter(node -> node.depth() == 2)
                    .filter(node -> NodeTraversalState.DEPTH_BOUNDARY.equals(node.traversalState()))
                    .count();
        }

        private CallNodeId rootNodeId() {
            return localNodes.values().stream()
                    .filter(node -> node.depth() == 0)
                    .findFirst()
                    .orElseThrow()
                    .nodeId();
        }

        private List<GraphNode> nodes() {
            return localNodes.values().stream()
                    .map(node -> node.graphNode(index))
                    .sorted(Comparator
                            .comparing((GraphNode node) -> node.target().map(MethodTarget::sourceFile).orElse("~"))
                            .thenComparing(node -> node.target().map(MethodTarget::packageName).orElse(""))
                            .thenComparing(node -> node.target().map(MethodTarget::className).orElse(""))
                            .thenComparing(node -> node.target().map(MethodTarget::methodName).orElse(""))
                            .thenComparing(node -> node.nodeId().value()))
                    .toList();
        }

        private void addEdge(MethodTarget caller, MethodTarget callee, DirectCallRelationship relationship) {
            CallNodeId callerId = localNodes.get(caller).nodeId();
            CallNodeId calleeId = localNodes.get(callee).nodeId();
            CallSiteRange range = callSite(caller, relationship.callSite());
            EdgeKey key = new EdgeKey(callerId, calleeId, range);
            if (!edgeKeys.add(key)) {
                return;
            }
            edges.add(new GraphEdge(
                    callerId,
                    calleeId,
                    range,
                    relationship.expression(),
                    relationship.strategy(),
                    relationship.evidence()));
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

        private record PendingDepthTwoRelationship(
                MethodTarget caller,
                MethodTarget callee,
                DirectCallRelationship relationship) {
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
                            dispatchKind(index), Optional.empty());
                }
                SourceMethodMetadata method = index.method(target).orElseThrow();
                CallSiteRange range = new CallSiteRange(
                        target.sourceFile(),
                        method.declarationLocation().range().start().line(),
                        method.declarationLocation().range().start().character(),
                        method.declarationLocation().range().end().line(),
                        method.declarationLocation().range().end().character());
                return new GraphNode(
                        nodeId,
                        Optional.of(target),
                        "",
                        contentState,
                        traversalState,
                        dispatchKind(index),
                        Optional.of(range));
            }

            private DispatchKind dispatchKind(RepositorySyntaxIndex index) {
                return index.method(target)
                        .filter(method -> method.annotationEvidence().stream()
                                .map(AnnotationEvidence::writtenName)
                                .anyMatch("Async"::equals))
                        .map(ignored -> DispatchKind.ASYNC)
                        .orElse(DispatchKind.SYNCHRONOUS);
            }
        }
    }
}
