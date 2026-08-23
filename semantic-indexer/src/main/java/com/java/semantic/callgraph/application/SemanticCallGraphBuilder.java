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
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Builds one deterministic, response-local outgoing fragment from syntax-proven method targets. */
public final class SemanticCallGraphBuilder {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, SemanticCallGraphBuilder::compareParameters);

    private final DirectCallRelationshipResolver relationshipResolver;
    private final GeneratedMemberEvidence generatedMemberEvidence;
    private final DataAccessEvidence dataAccessEvidence;

    public SemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            SpringImplementationSelector implementationSelector) {
        this(new DirectCallRelationshipResolver(semanticService, implementationSelector, new CanonicalTargetProjection()),
                new GeneratedMemberEvidence(), new DataAccessEvidence());
    }

    public SemanticCallGraphBuilder(
            DirectCallRelationshipResolver relationshipResolver,
            GeneratedMemberEvidence generatedMemberEvidence,
            DataAccessEvidence dataAccessEvidence) {
        this.relationshipResolver = Objects.requireNonNull(relationshipResolver, "relationshipResolver is required");
        this.generatedMemberEvidence = Objects.requireNonNull(
                generatedMemberEvidence, "generatedMemberEvidence is required");
        this.dataAccessEvidence = Objects.requireNonNull(dataAccessEvidence, "dataAccessEvidence is required");
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
        BuildState state = new BuildState(
                index, snapshot, requestedDepth, depthTwoNodeBudget, generatedMemberEvidence, dataAccessEvidence);
        state.addRoot(rootTarget);

        List<DirectCallRelationship> rootCalls;
        try {
            rootCalls = relationshipResolver.resolveAll(snapshot, index, rootTarget, root);
        } catch (DirectCallRelationshipResolver.ResolutionFailure exception) {
            throw exception.original();
        }
        for (DirectCallRelationship rootCall : rootCalls) {
            resolveAndAddDepthOne(state, rootTarget, rootCall);
        }
        if (requestedDepth == 2) {
            expandDepthOne(snapshot, index, state);
            state.hydrateDepthTwo();
        }
        return state.fragment();
    }

    private void resolveAndAddDepthOne(
            BuildState state,
            MethodTarget callerTarget,
            DirectCallRelationship relationship) {
        if (DirectCallRelationship.Status.LOCAL.equals(relationship.status())) {
            state.addDepthOneEdge(
                    callerTarget,
                    relationship.target().orElseThrow(),
                    relationship,
                    relationship.strategy(),
                    relationship.semanticMethod().orElseThrow());
        } else if (DirectCallRelationship.Status.EXTERNAL.equals(relationship.status())) {
            state.addExternalEdge(callerTarget, relationship.externalSymbol().orElseThrow(), relationship);
        } else if (DirectCallRelationship.Status.AMBIGUOUS.equals(relationship.status())) {
            state.addAmbiguous(callerTarget, relationship);
        } else {
            state.addUnresolved(callerTarget, relationship);
        }
    }

    private void expandDepthOne(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            BuildState state) {
        List<MethodTarget> depthOneTargets = state.depthOneTargets();
        for (MethodTarget depthOneTarget : depthOneTargets) {
            SemanticMethod depthOneMethod = state.semanticMethod(depthOneTarget).orElseThrow();
            List<DirectCallRelationship> calls;
            try {
                calls = relationshipResolver.resolveAll(snapshot, index, depthOneTarget, depthOneMethod);
            } catch (DirectCallRelationshipResolver.ResolutionFailure exception) {
                for (DirectCallRelationship relationship : exception.relationships()) {
                    resolveAndAddDepthTwo(state, depthOneTarget, relationship);
                }
                for (RuntimeException original : exception.originals()) {
                    state.addChildFailure(depthOneTarget, original);
                }
                continue;
            } catch (RuntimeException exception) {
                state.addChildFailure(depthOneTarget, exception);
                continue;
            }
            for (DirectCallRelationship relationship : calls) {
                resolveAndAddDepthTwo(state, depthOneTarget, relationship);
            }
        }
    }

    private void resolveAndAddDepthTwo(
            BuildState state,
            MethodTarget callerTarget,
            DirectCallRelationship relationship) {
        if (DirectCallRelationship.Status.LOCAL.equals(relationship.status())) {
            state.addDepthTwoEdge(
                    callerTarget,
                    relationship.target().orElseThrow(),
                    relationship,
                    relationship.strategy());
        } else if (DirectCallRelationship.Status.EXTERNAL.equals(relationship.status())) {
            state.addExternalEdge(callerTarget, relationship.externalSymbol().orElseThrow(), relationship);
        } else if (DirectCallRelationship.Status.AMBIGUOUS.equals(relationship.status())) {
            state.addAmbiguous(callerTarget, relationship);
        } else {
            state.addUnresolved(callerTarget, relationship);
        }
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
        private final GeneratedMemberEvidence generatedMemberEvidence;
        private final DataAccessEvidence dataAccessEvidence;
        private final CallTraversalState traversalState = new CallTraversalState();
        private final Map<MethodTarget, LocalNode> localNodes = new LinkedHashMap<>();
        private final Map<MethodTarget, SemanticMethod> semanticMethods = new LinkedHashMap<>();
        private final Map<String, CallNodeId> externalNodes = new LinkedHashMap<>();
        private final Map<String, NodeTraversalState> externalNodeTraversalStates = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<GraphWarning> warnings = new ArrayList<>();
        private final List<GraphError> errors = new ArrayList<>();
        private final List<PendingDepthTwoEdge> pendingDepthTwo = new ArrayList<>();
        private final Set<EdgeKey> edgeKeys = new LinkedHashSet<>();

        private BuildState(
                RepositorySyntaxIndex index,
                RepositorySnapshot snapshot,
                int requestedDepth,
                int budget,
                GeneratedMemberEvidence generatedMemberEvidence,
                DataAccessEvidence dataAccessEvidence) {
            this.index = index;
            this.snapshot = snapshot;
            this.requestedDepth = requestedDepth;
            this.budget = budget;
            this.generatedMemberEvidence = generatedMemberEvidence;
            this.dataAccessEvidence = dataAccessEvidence;
        }

        void addRoot(MethodTarget target) {
            localNodes.put(target, LocalNode.full(traversalState.localNodeId(target), target, 0, requestedDepth));
        }

        void addDepthOneEdge(
                MethodTarget caller,
                MethodTarget target,
                DirectCallRelationship relationship,
                ResolutionStrategy strategy,
                SemanticMethod semanticMethod) {
            LocalNode callee = localNodes.computeIfAbsent(target,
                    ignored -> LocalNode.full(traversalState.localNodeId(target), target, 1, requestedDepth));
            semanticMethods.putIfAbsent(target, semanticMethod);
            addEdge(caller, callee.nodeId(), relationship, strategy);
        }

        void addDepthTwoEdge(
                MethodTarget caller,
                MethodTarget target,
                DirectCallRelationship relationship,
                ResolutionStrategy strategy) {
            pendingDepthTwo.add(new PendingDepthTwoEdge(caller, target, relationship, strategy));
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
                addEdge(pending.caller(), target.nodeId(), pending.relationship(), pending.strategy());
            }
        }

        void addExternalEdge(MethodTarget caller, String symbol, DirectCallRelationship relationship) {
            CallNodeId nodeId = externalNodes.computeIfAbsent(symbol, traversalState::externalNodeId);
            addEdge(caller, nodeId, relationship, ResolutionStrategy.EXTERNAL_LIBRARY);
        }

        void addUnresolved(MethodTarget caller, DirectCallRelationship relationship) {
            Optional<EvidenceMatch> match = evidenceFor(relationship);
            if (match.isPresent()) {
                EvidenceMatch evidenceMatch = match.orElseThrow();
                addOpaqueEdge(caller, relationship, evidenceMatch);
                if (!ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE.equals(evidenceMatch.strategy())) {
                    return;
                }
            }
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_UNRESOLVED",
                    "descendant call target is not proven",
                    localNodes.get(caller).nodeId(),
                    Optional.of(relationship.expression()),
                    Optional.of(callSite(caller, relationship.callSite())),
                    List.of()));
        }

        /**
         * 依證據將本應標記為未解析的呼叫，重新標記為一個不透明終端節點的邊
         * <p>
         * declarationTarget 存在時優先採用資料存取證據；否則採用生成成員證據
         * <p>
         * 任一步驟無法明確判定即回傳空值，維持既有警告行為（fail-closed）
         */
        private Optional<EvidenceMatch> evidenceFor(DirectCallRelationship relationship) {
            if (relationship.declarationTarget().isPresent()) {
                return dataAccessEvidenceFor(relationship.declarationTarget().orElseThrow());
            }
            if (relationship.invocation().isEmpty()) {
                return Optional.empty();
            }
            return generatedMemberEvidenceFor(relationship.invocation().orElseThrow());
        }

        private Optional<EvidenceMatch> dataAccessEvidenceFor(MethodTarget declarationTarget) {
            Optional<SourceTypeMetadata> declaringType = index.sourceType(declarationTarget);
            Optional<SourceMethodMetadata> declaredMethod = index.method(declarationTarget);
            if (declaringType.isEmpty() || declaredMethod.isEmpty()) {
                return Optional.empty();
            }
            return dataAccessEvidence.evaluate(declaringType.orElseThrow(), declaredMethod.orElseThrow(), declarationTarget);
        }

        private Optional<EvidenceMatch> generatedMemberEvidenceFor(SyntaxInvocation invocation) {
            String receiverDeclaration = invocation.receiverDeclaration();
            if (!StringUtils.hasText(receiverDeclaration)) {
                return Optional.empty();
            }
            return receiverMetadata(receiverDeclaration)
                    .flatMap(metadata -> generatedMemberEvidence.evaluate(metadata, invocation));
        }

        /** builder chain 的 receiver（如 {@code X.XBuilder}）改以外層已標註型別重試一次 */
        private Optional<SourceTypeMetadata> receiverMetadata(String fullyQualifiedName) {
            List<SourceTypeMetadata> matches = index.sourceTypes(fullyQualifiedName);
            if (matches.size() == 1) {
                return Optional.of(matches.getFirst());
            }
            if (!matches.isEmpty() || !fullyQualifiedName.endsWith("Builder")) {
                return Optional.empty();
            }
            int lastDot = fullyQualifiedName.lastIndexOf('.');
            if (lastDot < 0) {
                return Optional.empty();
            }
            List<SourceTypeMetadata> outerMatches = index.sourceTypes(fullyQualifiedName.substring(0, lastDot));
            return outerMatches.size() == 1 ? Optional.of(outerMatches.getFirst()) : Optional.empty();
        }

        private void addOpaqueEdge(MethodTarget caller, DirectCallRelationship relationship, EvidenceMatch match) {
            CallNodeId nodeId = match.declarationTarget()
                    .map(this::opaqueLocalNodeId)
                    .orElseGet(() -> opaqueExternalNodeId(match.opaqueSymbol()));
            addEdge(caller, nodeId, relationship, match.strategy(), match.evidence());
        }

        private CallNodeId opaqueLocalNodeId(MethodTarget target) {
            LocalNode existing = localNodes.get(target);
            if (existing != null) { // cs-allow
                return existing.nodeId();
            }
            CallNodeId nodeId = traversalState.localNodeId(target);
            localNodes.put(target, LocalNode.opaque(nodeId, target));
            return nodeId;
        }

        private CallNodeId opaqueExternalNodeId(String symbol) {
            CallNodeId nodeId = externalNodes.computeIfAbsent(symbol, traversalState::externalNodeId);
            externalNodeTraversalStates.put(symbol, NodeTraversalState.OPAQUE);
            return nodeId;
        }

        void addAmbiguous(MethodTarget caller, DirectCallRelationship relationship) {
            warnings.add(new GraphWarning(
                    "DESCENDANT_CALL_AMBIGUOUS",
                    "descendant call has multiple exact targets",
                    localNodes.get(caller).nodeId(),
                    Optional.of(relationship.expression()),
                    Optional.of(callSite(caller, relationship.callSite())),
                    relationship.candidates().stream().sorted(TARGET_ORDER).toList()));
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
                        externalNodeTraversalStates.getOrDefault(external.getKey(), NodeTraversalState.EXTERNAL),
                        DispatchKind.SYNCHRONOUS,
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
                DirectCallRelationship relationship,
                ResolutionStrategy strategy) {
            addEdge(caller, callee, relationship, strategy, relationship.evidence());
        }

        private void addEdge(
                MethodTarget caller,
                CallNodeId callee,
                DirectCallRelationship relationship,
                ResolutionStrategy strategy,
                List<String> evidence) {
            CallNodeId callerId = localNodes.get(caller).nodeId();
            CallSiteRange range = callSite(caller, relationship.callSite());
            EdgeKey key = new EdgeKey(callerId, callee, range);
            if (!edgeKeys.add(key)) {
                return;
            }
            edges.add(new GraphEdge(callerId, callee, range, relationship.expression(), strategy, evidence));
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
                DirectCallRelationship relationship,
                ResolutionStrategy strategy) {
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

            /** 證據判定為資料存取進入點的不透明終端節點；depth 固定為 2，不參與 depth-one 展開或 depth-two 水合 */
            static LocalNode opaque(CallNodeId nodeId, MethodTarget target) {
                return new LocalNode(nodeId, target, 2, NodeContentState.TARGET_ONLY, NodeTraversalState.OPAQUE);
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
