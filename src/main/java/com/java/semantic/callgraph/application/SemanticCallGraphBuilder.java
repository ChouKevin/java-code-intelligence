package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.CallNode;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.FlattenedMethodNode;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.identity.PolicyIdentity;
import org.springframework.util.StringUtils;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import java.io.IOException;

/** Builds one deterministic permitted-view call graph from revision-bound domain snapshots. */
public final class SemanticCallGraphBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticCallGraphBuilder.class);

    private static final String EXTERNAL_WARNING =
            "Target identity resolved; external body unavailable; do not infer internal behavior";
    private static final String FEIGN_WARNING =
            "Target identity resolved; Feign client body unavailable; do not infer remote behavior";
    private static final String SPRING_AMBIGUITY_WARNING =
            "Multiple Spring implementations remain possible; do not present any candidate as the proven target";
    private static final String UNRESOLVED_WARNING =
            "Target identity is not proven; do not present a candidate as fact";

    private static final Comparator<PendingCall> CALL_ORDER = Comparator
            .comparing(PendingCall::targetPackage)
            .thenComparing(PendingCall::targetClass)
            .thenComparing(PendingCall::targetMethod)
            .thenComparing(PendingCall::targetParameters)
            .thenComparing(PendingCall::targetUri)
            .thenComparingInt(PendingCall::targetStartLine)
            .thenComparingInt(PendingCall::targetStartCharacter)
            .thenComparingInt(PendingCall::targetEndLine)
            .thenComparingInt(PendingCall::targetEndCharacter)
            .thenComparingInt(call -> call.callSite().start().line())
            .thenComparingInt(call -> call.callSite().start().character())
            .thenComparingInt(call -> call.callSite().end().line())
            .thenComparingInt(call -> call.callSite().end().character())
            .thenComparing(PendingCall::rawSignature);

    private final JavaSemanticService semanticService;
    private final CallGraphClassifier classifier;
    private final SpringImplementationSelector springSelector;
    private final ReadPolicy readPolicy;

    public SemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            CallGraphClassifier classifier,
            SpringImplementationSelector springSelector,
            ReadPolicy readPolicy) {
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
        this.springSelector = Objects.requireNonNull(springSelector, "springSelector is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
    }

    public CallGraphBuildResult build(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            SemanticMethod root,
            int maxDepth) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(syntax, "syntax is required");
        Objects.requireNonNull(root, "root is required");
        Assert.isTrue(maxDepth > 0, "maxDepth must be positive");

        String repoId = snapshot.repositoryId().value();
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(repoId, syntax);
        CallTraversalState state = new CallTraversalState();
        Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence = new HashMap<>();
        MethodId rootId = methodId(repoId, root);
        traverse(snapshot, index, root, 0, maxDepth, state, methodEvidence);

        ExplainableCallGraph traversed = new ExplainableCallGraph(
                rootId, state.nodes(), state.edges(), Map.of(), flattened(rootId, state.nodes(), state.edges()));
        EvidenceEnricher.EnrichmentResult enrichment = new EvidenceEnricher(readPolicy)
                .enrich(traversed, index, methodEvidence);
        ExplainableCallGraph graph = new RestrictedEvidenceRedactor(readPolicy).redact(
                enrichment.graph(), enrichment.relatedClassEvidence());
        return new CallGraphBuildResult(
                graph, state.warnings(), state.errors(), !CollectionUtils.isEmpty(state.errors()));
    }

    private CallNodeId traverse(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod method,
            int depth,
            int maxDepth,
            CallTraversalState state,
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence) {
        String repoId = snapshot.repositoryId().value();
        MethodId methodId = methodId(repoId, method);
        EvidenceVisibility visibility = visibility(repoId, methodId);
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility)) {
            return state.restrictedNode(methodId, nodeId -> restrictedNode(nodeId));
        }
        if (state.onCurrentPath(methodId)) {
            return state.readableNodeId(methodId).orElseThrow();
        }

        CallType callType = depth == maxDepth
                ? CallType.TRAVERSAL_CUTOFF
                : classifiedCallType(method, index);
        CallNodeId nodeId = CallType.TRAVERSAL_CUTOFF.equals(callType)
                ? state.occurrenceNode(created -> readableNode(
                        created, method, methodId, index, callType, visibility))
                : state.readableNode(
                        methodId, created -> readableNode(created, method, methodId, index, callType, visibility));
        if (depth == maxDepth) {
            methodEvidence.put(methodId, cutoffEvidence(snapshot, index, method).methodEvidence());
        }
        if (depth == maxDepth || nonTraversable(callType)) {
            return nodeId;
        }

        state.enter(methodId);
        try {
            List<PendingCall> calls = pendingCalls(snapshot, index, method);
            for (PendingCall call : calls) {
                expandCall(snapshot, index, method, nodeId, call, depth, maxDepth, state, methodEvidence);
            }
        } finally {
            state.leave(methodId);
        }
        return nodeId;
    }

    private List<PendingCall> pendingCalls(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller) {
        List<PendingCall> pending = new ArrayList<>();
        Set<SemanticRange> covered = new HashSet<>();
        List<SemanticCall> hierarchy = semanticService.outgoingCalls(snapshot, caller);
        for (SemanticCall call : hierarchy) {
            for (SemanticRange callSite : call.callSites()) {
                covered.add(callSite);
                pending.add(new PendingCall(
                        call.target(), call.rawSignature(), callSite, call.external(),
                        ResolutionStrategy.JDT_CALL_HIERARCHY, invocation(index, caller, callSite),
                        call.status(), Optional.empty()));
            }
        }
        for (SyntaxInvocation invocation : invocations(index, caller)) {
            SemanticRange range = semanticRange(invocation.range());
            if (covered.contains(range)) {
                continue;
            }
            Optional<SemanticCall> fallback;
            try {
                fallback = semanticService.resolveCallAt(snapshot, caller, semanticCallSite(invocation));
            } catch (RuntimeException exception) {
                pending.add(new PendingCall(
                        Optional.empty(), invocation.expression(), range, false,
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK, invocation,
                        SemanticCallStatus.CONVERSION_FAILED, Optional.of(exception)));
                continue;
            }
            if (fallback.isPresent()) {
                SemanticCall resolved = fallback.orElseThrow();
                pending.add(new PendingCall(
                        resolved.target(), resolved.rawSignature(), range, resolved.external(),
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK, invocation,
                        resolved.status(), Optional.empty()));
            } else {
                pending.add(new PendingCall(
                        Optional.empty(), invocation.expression(), range, false,
                        ResolutionStrategy.JDT_DEFINITION_FALLBACK, invocation,
                        SemanticCallStatus.IDENTITY_UNPROVEN, Optional.empty()));
            }
        }
        return pending.stream().sorted(CALL_ORDER).toList();
    }

    private void expandCall(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller,
            CallNodeId callerNodeId,
            PendingCall call,
            int depth,
            int maxDepth,
            CallTraversalState state,
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence) {
        if (call.failure().isPresent()) {
            publishDefinitionFallbackFailure(
                    snapshot, index, callerNodeId, caller, call, state,
                    call.failure().orElseThrow());
            return;
        }
        if (SemanticCallStatus.CONVERSION_FAILED.equals(call.semanticStatus())) {
            publishTargetConversionFailure(snapshot, index, callerNodeId, caller, call, state);
            return;
        }
        if (!call.target().isPresent()) {
            addUnresolvedEdge(
                    snapshot, index, callerNodeId, caller, call, state, List.of("semantic target identity unavailable"));
            return;
        }
        SemanticMethod semanticTarget = call.target().orElseThrow();
        MethodId targetId = methodId(snapshot.repositoryId().value(), semanticTarget);
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                visibility(snapshot.repositoryId().value(), targetId))) {
            addResolvedTarget(snapshot, index, caller, callerNodeId, semanticTarget, call,
                    ResolutionStrategy.BUSINESS_READ_FORBIDDEN, depth, maxDepth, state, false, methodEvidence);
            return;
        }
        if (call.external()) {
            addResolvedTarget(snapshot, index, caller, callerNodeId, semanticTarget, call,
                    ResolutionStrategy.EXTERNAL_LIBRARY, depth, maxDepth, state, false, methodEvidence);
            return;
        }

        ResolutionStrategy classifiedStrategy = classifiedStrategy(index, semanticTarget);
        ResolutionStrategy directStrategy = ResolutionStrategy.JDT_CALL_HIERARCHY.equals(classifiedStrategy)
                ? call.strategy()
                : classifiedStrategy;
        Optional<ClassMetadata> targetMetadata = metadata(index, semanticTarget);
        if (targetMetadata.isPresent()
                && TypeKind.INTERFACE.equals(targetMetadata.orElseThrow().kind())
                && ResolutionStrategy.JDT_CALL_HIERARCHY.equals(classifiedStrategy)) {
            expandImplementations(
                    snapshot, index, caller, callerNodeId, semanticTarget, call, depth, maxDepth, state,
                    methodEvidence);
            return;
        }
        boolean recurse = !ResolutionStrategy.FEIGN_CLIENT.equals(directStrategy);
        addResolvedTarget(
                snapshot, index, caller, callerNodeId, semanticTarget, call,
                directStrategy, depth, maxDepth, state, recurse, methodEvidence);
    }

    private void expandImplementations(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller,
            CallNodeId callerNodeId,
            SemanticMethod contract,
            PendingCall call,
            int depth,
            int maxDepth,
            CallTraversalState state,
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence) {
        List<SemanticMethod> implementations;
        try {
            implementations = semanticService.implementations(snapshot, contract);
        } catch (RuntimeException exception) {
            publishChildQueryFailure(
                    snapshot, index, caller, callerNodeId, contract, call, state, exception);
            return;
        }
        if (CollectionUtils.isEmpty(implementations)) {
            addUnresolvedEdge(
                    snapshot, index, callerNodeId, caller, call, state, List.of("no implementation target resolved"));
            return;
        }
        List<ImplementationCandidate> candidates = implementations.stream()
                .map(method -> candidate(index, method))
                .toList();
        ImplementationSelection selection = springSelector.select(call.invocation(), candidates);
        for (String warning : selection.warnings()) {
            state.addWarning(new AnalysisWarning(
                    "AMBIGUOUS_SPRING_IMPLEMENTATION", warning, location(caller, call.callSite())));
        }
        for (SemanticMethod selected : selection.candidates()) {
            PendingCall selectedCall = call.withTarget(selected);
            addResolvedTarget(snapshot, index, caller, callerNodeId, selected, selectedCall,
                    selection.strategy(), depth, maxDepth, state, true, methodEvidence);
        }
    }

    private void addResolvedTarget(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller,
            CallNodeId callerNodeId,
            SemanticMethod target,
            PendingCall call,
            ResolutionStrategy strategy,
            int depth,
            int maxDepth,
            CallTraversalState state,
            boolean recurse,
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence) {
        MethodId targetId = methodId(snapshot.repositoryId().value(), target);
        CallTraversalState.EdgeKey edgeKey = edgeKey(callerNodeId, targetId.toString(), call.callSite());
        if (state.containsEdge(edgeKey)) {
            return;
        }
        EvidenceVisibility visibility = visibility(snapshot.repositoryId().value(), targetId);
        CallNodeId calleeNodeId;
        Optional<CallTraversalState.CutoffEvidenceOutcome> cutoffOutcome = Optional.empty();
        Optional<CallTraversalState.OccurrenceCheckpoint> recursiveCheckpoint = Optional.empty();
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility)) {
            calleeNodeId = state.restrictedNode(targetId, nodeId -> restrictedNode(nodeId));
            strategy = ResolutionStrategy.BUSINESS_READ_FORBIDDEN;
            recurse = false;
        } else if (state.onCurrentPath(targetId)) {
            calleeNodeId = state.readableNodeId(targetId).orElseThrow();
            recurse = false;
        } else {
            int childDepth = depth + 1;
            CallType targetType = targetCallType(target, index, strategy, childDepth, maxDepth);
            boolean willRecurse = recurse && childDepth != maxDepth && !nonTraversable(targetType);
            if (willRecurse) {
                recursiveCheckpoint = Optional.of(state.checkpoint(methodEvidence));
            }
            if (CallType.TRAVERSAL_CUTOFF.equals(targetType)) {
                CallTraversalState.CutoffEvidenceOutcome outcome = cutoffEvidenceOutcome(
                        snapshot, index, target, targetId, state);
                cutoffOutcome = Optional.of(outcome);
                if (outcome.evidence().isPresent()) {
                    methodEvidence.put(targetId, outcome.evidence().orElseThrow());
                }
            }
            if (cutoffOutcome.stream().anyMatch(outcome -> outcome.error().isPresent())) {
                calleeNodeId = state.occurrenceNode(
                        nodeId -> unresolvedNode(nodeId, target, targetId, index));
                strategy = ResolutionStrategy.UNRESOLVED_TARGET;
            } else if (CallType.TRAVERSAL_CUTOFF.equals(targetType)) {
                calleeNodeId = state.occurrenceNode(
                        nodeId -> readableNode(nodeId, target, targetId, index, targetType, visibility));
            } else {
                calleeNodeId = state.readableNode(
                        targetId, nodeId -> readableNode(nodeId, target, targetId, index, targetType, visibility));
            }
            if (childDepth == maxDepth || nonTraversable(targetType)) {
                recurse = false;
            }
        }

        CallEdge edge = edge(snapshot, index, callerNodeId, calleeNodeId, caller, call, strategy, visibility);
        if (!state.addEdge(edge, edgeKey)) {
            return;
        }
        if (cutoffOutcome.stream().anyMatch(outcome -> outcome.error().isPresent())) {
            return;
        }
        cutoffOutcome.ifPresent(outcome -> publishCutoffDefinitionFailures(
                snapshot, index, target, calleeNodeId, outcome.definitionFallbackFailures(), state));
        cutoffOutcome.ifPresent(outcome -> publishCutoffConversionFailures(
                snapshot, index, target, calleeNodeId, outcome.conversionFailures(), state));
        if (!recurse) {
            return;
        }
        try {
            traverse(snapshot, index, target, depth + 1, maxDepth, state, methodEvidence);
        } catch (RuntimeException exception) {
            CallTraversalState.OccurrenceCheckpoint checkpoint = recursiveCheckpoint.orElseThrow();
            state.rollback(checkpoint);
            methodEvidence.clear();
            methodEvidence.putAll(checkpoint.methodEvidence());
            publishChildQueryFailure(
                    snapshot, index, caller, callerNodeId, target, call, state, exception);
        }
    }

    private void publishChildQueryFailure(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod caller,
            CallNodeId callerNodeId,
            SemanticMethod target,
            PendingCall call,
            CallTraversalState state,
            RuntimeException exception) {
        MethodId targetId = methodId(snapshot.repositoryId().value(), target);
        CallTraversalState.EdgeKey edgeKey = edgeKey(callerNodeId, targetId.toString(), call.callSite());
        if (state.containsEdge(edgeKey)) {
            return;
        }
        EvidenceVisibility visibility = visibility(snapshot.repositoryId().value(), targetId);
        CallNodeId unresolvedNodeId = state.occurrenceNode(
                nodeId -> unresolvedNode(nodeId, target, targetId, index));
        CallEdge unresolvedEdge = edge(
                snapshot, index, callerNodeId, unresolvedNodeId, caller, call,
                ResolutionStrategy.UNRESOLVED_TARGET, visibility);
        state.addEdge(unresolvedEdge, edgeKey);
        logChildFailure(snapshot.repositoryId().value(), exception);
        state.addError(childFailureError(exception));
    }

    private void publishDefinitionFallbackFailure(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            CallNodeId callerNodeId,
            SemanticMethod caller,
            PendingCall call,
            CallTraversalState state,
            RuntimeException exception) {
        addUnresolvedEdge(snapshot, index, callerNodeId, caller, call, state, List.of());
        logChildFailure(snapshot.repositoryId().value(), exception);
        state.addError(childFailureError(exception));
    }

    private void publishTargetConversionFailure(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            CallNodeId callerNodeId,
            SemanticMethod caller,
            PendingCall call,
            CallTraversalState state) {
        addUnresolvedEdge(snapshot, index, callerNodeId, caller, call, state, List.of());
        recordTargetConversionFailure(snapshot, state);
    }

    private void publishCutoffDefinitionFailures(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod cutoff,
            CallNodeId cutoffNodeId,
            List<CallTraversalState.DefinitionFallbackFailure> failures,
            CallTraversalState state) {
        for (CallTraversalState.DefinitionFallbackFailure failure : failures) {
            PendingCall failedCall = new PendingCall(
                    Optional.empty(), failure.rawSignature(), failure.callSite(), false,
                    ResolutionStrategy.JDT_DEFINITION_FALLBACK, failure.invocation(),
                    SemanticCallStatus.IDENTITY_UNPROVEN, Optional.empty());
            addUnresolvedEdge(snapshot, index, cutoffNodeId, cutoff, failedCall, state, List.of());
        }
    }

    private void publishCutoffConversionFailures(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod cutoff,
            CallNodeId cutoffNodeId,
            List<CallTraversalState.ConversionFailure> failures,
            CallTraversalState state) {
        for (CallTraversalState.ConversionFailure failure : failures) {
            PendingCall failedCall = new PendingCall(
                    Optional.empty(), failure.rawSignature(), failure.callSite(), false,
                    ResolutionStrategy.JDT_CALL_HIERARCHY, failure.invocation(),
                    SemanticCallStatus.CONVERSION_FAILED, Optional.empty());
            addUnresolvedEdge(snapshot, index, cutoffNodeId, cutoff, failedCall, state, List.of());
        }
    }

    private void recordTargetConversionFailure(
            RepositorySnapshot snapshot,
            CallTraversalState state) {
        LOGGER.warn("Call graph child query failed repoId={} category={}",
                snapshot.repositoryId().value(), "TARGET_CONVERSION_FAILED");
        state.addError(targetConversionFailureError());
    }

    private AnalysisError targetConversionFailureError() {
        return new AnalysisError(
                "CHILD_SEMANTIC_QUERY_FAILED", "Unable to analyze one call target",
                "TARGET_CONVERSION_FAILED");
    }

    static void logChildFailure(String repositoryId, RuntimeException exception) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(exception, "exception is required");
        LOGGER.warn("Call graph child query failed repoId={} category={} exceptionType={}",
                repositoryId, "CHILD_SEMANTIC_QUERY_FAILED", exception.getClass().getSimpleName());
    }

    static AnalysisError childFailureError(RuntimeException exception) {
        Objects.requireNonNull(exception, "exception is required");
        return new AnalysisError(
                "CHILD_SEMANTIC_QUERY_FAILED", "Unable to analyze one call target",
                exception.getClass().getSimpleName());
    }

    private CallTraversalState.CutoffEvidenceOutcome cutoffEvidenceOutcome(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod target,
            MethodId targetId,
            CallTraversalState state) {
        Optional<CallTraversalState.CutoffEvidenceOutcome> existing = state.cutoffEvidenceOutcome(targetId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        CallTraversalState.CutoffEvidenceOutcome outcome;
        try {
            CutoffEvidence evidence = cutoffEvidence(snapshot, index, target);
            outcome = CallTraversalState.CutoffEvidenceOutcome.success(
                    evidence.methodEvidence(), evidence.definitionFallbackFailures(), evidence.conversionFailures());
            for (CallTraversalState.DefinitionFallbackFailure failure
                    : outcome.definitionFallbackFailures()) {
                logChildFailure(snapshot.repositoryId().value(), failure.exception());
                state.addError(childFailureError(failure.exception()));
            }
            for (CallTraversalState.ConversionFailure ignored : outcome.conversionFailures()) {
                recordTargetConversionFailure(snapshot, state);
            }
        } catch (RuntimeException exception) {
            AnalysisError error = childFailureError(exception);
            outcome = CallTraversalState.CutoffEvidenceOutcome.failure(error);
            logChildFailure(snapshot.repositoryId().value(), exception);
            state.addError(error);
        }
        state.recordCutoffEvidenceOutcome(targetId, outcome);
        return outcome;
    }

    private CutoffEvidence cutoffEvidence(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod method) {
        List<PendingCall> calls = pendingCalls(snapshot, index, method);
        List<String> signatures = calls.stream()
                .filter(call -> !call.failure().isPresent())
                .filter(call -> !SemanticCallStatus.CONVERSION_FAILED.equals(call.semanticStatus()))
                .map(PendingCall::rawSignature)
                .distinct()
                .sorted()
                .toList();
        boolean containsForbiddenReference = calls.stream()
                .filter(call -> !call.failure().isPresent())
                .filter(call -> !SemanticCallStatus.CONVERSION_FAILED.equals(call.semanticStatus()))
                .map(PendingCall::target)
                .flatMap(Optional::stream)
                .map(target -> methodId(snapshot.repositoryId().value(), target))
                .anyMatch(target -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                        visibility(snapshot.repositoryId().value(), target)));
        List<CallTraversalState.DefinitionFallbackFailure> failures = calls.stream()
                .filter(call -> call.failure().isPresent())
                .map(call -> new CallTraversalState.DefinitionFallbackFailure(
                        call.rawSignature(), call.callSite(), call.invocation(), call.failure().orElseThrow()))
                .toList();
        List<CallTraversalState.ConversionFailure> conversionFailures = calls.stream()
                .filter(call -> !call.failure().isPresent())
                .filter(call -> SemanticCallStatus.CONVERSION_FAILED.equals(call.semanticStatus()))
                .map(call -> new CallTraversalState.ConversionFailure(
                        call.rawSignature(), call.callSite(), call.invocation()))
                .toList();
        return new CutoffEvidence(
                new EvidenceEnricher.MethodEvidence(signatures, containsForbiddenReference),
                failures,
                conversionFailures);
    }

    private void addUnresolvedEdge(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            CallNodeId callerNodeId,
            SemanticMethod caller,
            PendingCall call,
            CallTraversalState state,
            List<String> warnings) {
        CallTraversalState.EdgeKey edgeKey = edgeKey(callerNodeId, call.rawSignature(), call.callSite());
        if (state.containsEdge(edgeKey)) {
            return;
        }
        EvidenceVisibility visibility = unresolvedVisibility(snapshot.repositoryId().value(), call);
        CallNodeId unresolvedNode = state.occurrenceNode(nodeId ->
                EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility)
                        ? restrictedNode(nodeId)
                        : new CallNode(
                                nodeId, null, "", CallType.UNRESOLVED, "", null, null,
                                Map.of(), "", EvidenceVisibility.READABLE));
        boolean restricted = EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility);
        List<String> unresolvedWarnings = new ArrayList<>(warnings);
        unresolvedWarnings.add(UNRESOLVED_WARNING);
        CallEdge edge = new CallEdge(
                callerNodeId, unresolvedNode,
                restricted ? "" : callExpression(call),
                restricted ? null : callSite(snapshot, index, caller, call.callSite()).orElse(null),
                restricted ? ResolutionStrategy.BUSINESS_READ_FORBIDDEN : ResolutionStrategy.UNRESOLVED_TARGET,
                restricted ? 1.0 : 0.0,
                restricted ? List.of() : List.of(call.rawSignature()),
                restricted
                        ? List.of(RestrictedEvidenceRedactor.GENERIC_WARNING)
                        : unresolvedWarnings.stream().distinct().toList(),
                visibility);
        state.addEdge(edge, edgeKey);
    }

    private EvidenceVisibility unresolvedVisibility(String repoId, PendingCall call) {
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOfRepository(repoId))) {
            return EvidenceVisibility.BUSINESS_READ_FORBIDDEN;
        }
        return call.invocation().resolvedTarget()
                .filter(target -> forbiddenInvocationTarget(repoId, target))
                .map(target -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN)
                .orElse(EvidenceVisibility.READABLE);
    }

    private boolean forbiddenInvocationTarget(String repoId, InvocationTarget target) {
        String className = PolicyIdentity.className(target.packageName(), target.className());
        TypeId typeId = new TypeId(repoId, target.packageName(), className);
        MethodId methodId = new MethodId(
                repoId, target.packageName(), className, target.methodName(),
                PolicyIdentity.parameterTypes(target.parameterTypes()));
        return EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(typeId))
                || EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(methodId));
    }

    private CallEdge edge(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            CallNodeId callerNodeId,
            CallNodeId calleeNodeId,
            SemanticMethod caller,
            PendingCall call,
            ResolutionStrategy strategy,
            EvidenceVisibility visibility) {
        boolean restricted = EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility);
        return new CallEdge(
                callerNodeId,
                calleeNodeId,
                restricted ? "" : callExpression(call),
                restricted ? null : callSite(snapshot, index, caller, call.callSite()).orElse(null),
                strategy,
                confidence(strategy),
                restricted ? List.of() : List.of(call.rawSignature()),
                restricted ? List.of() : warningsFor(strategy),
                visibility);
    }

    private CallNode readableNode(
            CallNodeId nodeId,
            SemanticMethod method,
            MethodId methodId,
            RepositorySyntaxIndex index,
            CallType callType,
            EvidenceVisibility visibility) {
        Optional<ClassMetadata> metadata = metadata(index, method);
        Optional<MethodSignature> syntaxMethod = syntaxMethod(index, method);
        String sourceFile = metadata.map(ClassMetadata::filePath).orElseGet(() -> sourceFile(method));
        Integer startLine = syntaxMethod.map(value -> value.startLine()).orElse(method.location().range().start().line() + 1);
        Integer endLine = syntaxMethod.map(value -> value.endLine()).orElse(method.location().range().end().line() + 1);
        String code = syntaxMethod.map(value -> value.source().text()).orElse("");
        return new CallNode(
                nodeId, methodId, signature(method), callType, sourceFile, startLine, endLine,
                Map.of(), code, visibility);
    }

    private CallNode restrictedNode(CallNodeId nodeId) {
        return new CallNode(
                nodeId, null, "", CallType.UNRESOLVED, "", null, null,
                Map.of(), "", EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
    }

    private CallNode unresolvedNode(
            CallNodeId nodeId,
            SemanticMethod method,
            MethodId methodId,
            RepositorySyntaxIndex index) {
        CallNode readable = readableNode(
                nodeId, method, methodId, index, CallType.UNRESOLVED, EvidenceVisibility.READABLE);
        return new CallNode(
                readable.nodeId(), readable.methodId(), readable.signature(), CallType.UNRESOLVED,
                readable.sourceFile(), readable.startLine(), readable.endLine(), readable.annotations(), "",
                readable.visibility());
    }

    private ImplementationCandidate candidate(RepositorySyntaxIndex index, SemanticMethod method) {
        Optional<ClassMetadata> metadata = metadata(index, method);
        return new ImplementationCandidate(
                method,
                metadata.map(ClassMetadata::primary).orElse(false),
                metadata.map(ClassMetadata::beanQualifiers).orElse(List.of()),
                metadata.map(ClassMetadata::profiles).orElse(List.of()));
    }

    private ResolutionStrategy classifiedStrategy(RepositorySyntaxIndex index, SemanticMethod method) {
        Optional<ClassMetadata> metadata = metadata(index, method);
        if (!metadata.isPresent()) {
            return ResolutionStrategy.JDT_CALL_HIERARCHY;
        }
        Optional<ClassMetadata> builderOwner = builderOwner(index, method);
        Optional<MethodSignature> syntaxMethod = syntaxMethod(index, method);
        return syntaxMethod
                .map(value -> classifier.resolutionStrategy(
                        metadata.orElseThrow(), value, method.returnType(), builderOwner))
                .orElseGet(() -> classifier.resolutionStrategy(
                        metadata.orElseThrow(), method.methodName(), method.parameterTypes(),
                        method.returnType(), builderOwner));
    }

    private CallType targetCallType(
            SemanticMethod target,
            RepositorySyntaxIndex index,
            ResolutionStrategy strategy,
            int childDepth,
            int maxDepth) {
        if (ResolutionStrategy.EXTERNAL_LIBRARY.equals(strategy)) {
            return CallType.EXTERNAL_LIB;
        }
        CallType classified = classifiedCallType(target, index);
        if (nonTraversable(classified)) {
            return classified;
        }
        return childDepth == maxDepth ? CallType.TRAVERSAL_CUTOFF : classified;
    }

    private CallType classifiedCallType(SemanticMethod method, RepositorySyntaxIndex index) {
        return metadata(index, method)
                .map(value -> classifier.callType(
                        value, method.methodName(), method.parameterTypes(), method.returnType(),
                        builderOwner(index, method)))
                .orElse(CallType.INTERNAL_CLASS);
    }

    private Optional<ClassMetadata> builderOwner(
            RepositorySyntaxIndex index, SemanticMethod method) {
        int lastDot = method.className().lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        String ownerClassName = method.className().substring(0, lastDot);
        return index.classes(method.packageName() + "." + ownerClassName).stream().findFirst();
    }

    private Optional<ClassMetadata> metadata(RepositorySyntaxIndex index, SemanticMethod method) {
        return index.classes(method.packageName() + "." + method.className()).stream()
                .filter(candidate -> candidate.methods().stream().anyMatch(signature -> matches(signature, method)))
                .findFirst()
                .or(() -> index.classes(method.packageName() + "." + method.className()).stream().findFirst());
    }

    private Optional<MethodSignature> syntaxMethod(RepositorySyntaxIndex index, SemanticMethod method) {
        return metadata(index, method).stream()
                .flatMap(value -> value.methods().stream())
                .filter(candidate -> matches(candidate, method))
                .findFirst();
    }

    private List<SyntaxInvocation> invocations(RepositorySyntaxIndex index, SemanticMethod caller) {
        return syntaxMethod(index, caller).map(MethodSignature::invocations).orElse(List.of());
    }

    private SyntaxInvocation invocation(
            RepositorySyntaxIndex index, SemanticMethod caller, SemanticRange callSite) {
        return invocations(index, caller).stream()
                .filter(candidate -> semanticRange(candidate.range()).equals(callSite))
                .findFirst()
                .orElseGet(() -> new SyntaxInvocation(
                        InvocationKind.METHOD,
                        syntaxRange(callSite),
                        "semantic call",
                        "",
                        "",
                        "",
                        Optional.empty(),
                        syntaxRange(callSite).start()));
    }

    private EvidenceVisibility visibility(String repoId, MethodId methodId) {
        EvidenceVisibility repositoryVisibility = readPolicy.visibilityOfRepository(repoId);
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(repositoryVisibility)) {
            return EvidenceVisibility.BUSINESS_READ_FORBIDDEN;
        }
        return readPolicy.visibilityOf(methodId);
    }

    private boolean nonTraversable(CallType callType) {
        return CallType.RPC_CLIENT.equals(callType)
                || CallType.DATA_ACCESS.equals(callType)
                || CallType.GENERATED_CODE.equals(callType)
                || CallType.EXTERNAL_LIB.equals(callType)
                || CallType.CYCLE_BACK_EDGE.equals(callType)
                || CallType.UNRESOLVED.equals(callType)
                || CallType.TRAVERSAL_CUTOFF.equals(callType);
    }

    private FlattenedCallGraph flattened(MethodId root, List<CallNode> nodes, List<CallEdge> edges) {
        List<FlattenedMethodNode> methods = nodes.stream()
                .filter(node -> EvidenceVisibility.READABLE.equals(node.visibility()))
                .map(node -> new FlattenedMethodNode(
                        node.signature(),
                        Optional.ofNullable(node.methodId()).map(MethodId::className).orElse(""),
                        Optional.ofNullable(node.methodId()).map(MethodId::methodName).orElse(""),
                        node.callType(), "", node.code(), node.annotations(), callees(node, nodes, edges)))
                .toList();
        return new FlattenedCallGraph(methods, methodSignature(root), Map.of());
    }

    private List<String> callees(CallNode node, List<CallNode> nodes, List<CallEdge> edges) {
        return edges.stream()
                .filter(edge -> edge.caller().equals(node.nodeId()))
                .map(CallEdge::callee)
                .map(callee -> nodes.stream().filter(nodeCandidate -> nodeCandidate.nodeId().equals(callee)).findFirst())
                .flatMap(Optional::stream)
                .map(CallNode::signature)
                .toList();
    }

    private boolean matches(MethodSignature signature, SemanticMethod method) {
        return signature.name().equals(method.methodName())
                && signature.paramTypes().equals(method.parameterTypes());
    }

    private MethodId methodId(String repoId, SemanticMethod method) {
        return new MethodId(
                repoId,
                method.packageName(),
                PolicyIdentity.className(method.packageName(), method.className()),
                method.methodName(),
                PolicyIdentity.parameterTypes(method.parameterTypes()));
    }

    private String signature(SemanticMethod method) {
        return method.packageName() + "." + method.className() + "." + methodSignature(method);
    }

    private String methodSignature(SemanticMethod method) {
        return method.methodName() + "(" + String.join(",", method.parameterTypes()) + ")";
    }

    private String methodSignature(MethodId method) {
        return method.methodName() + "(" + String.join(",", method.parameterTypes()) + ")";
    }

    private String sourceFile(SemanticMethod method) {
        return "";
    }

    private String sourceFile(RepositorySyntaxIndex index, SemanticMethod method) {
        return metadata(index, method).map(ClassMetadata::filePath).orElseGet(() -> sourceFile(method));
    }

    private Optional<CallSiteRange> callSite(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod caller, SemanticRange range) {
        return sourceFile(snapshot, index, caller).map(sourceFile -> new CallSiteRange(
                sourceFile,
                range.start().line() + 1,
                range.start().character() + 1,
                range.end().line() + 1,
                range.end().character() + 1));
    }

    private Optional<String> sourceFile(
            RepositorySnapshot snapshot, RepositorySyntaxIndex index, SemanticMethod method) {
        Optional<String> metadataPath = metadata(index, method).map(ClassMetadata::filePath)
                .filter(StringUtils::hasText);
        if (metadataPath.isPresent()) {
            return metadataPath;
        }
        try {
            URI uri = URI.create(method.location().uri());
            if (!"file".equals(uri.getScheme())) {
                return Optional.empty();
            }
            Path root = snapshot.root().toRealPath();
            Path source = Path.of(uri).toRealPath();
            if (!source.startsWith(root)) {
                return Optional.empty();
            }
            return Optional.of(root.relativize(source).toString().replace('\\', '/'));
        } catch (RuntimeException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }


    private String callExpression(PendingCall call) {
        return "semantic call".equals(call.invocation().expression())
                ? call.rawSignature()
                : call.invocation().expression();
    }

    private String location(SemanticMethod caller, SemanticRange range) {
        return sourceFile(caller) + ":" + (range.start().line() + 1);
    }

    private List<String> warningsFor(ResolutionStrategy strategy) {
        return switch (strategy) {
            case EXTERNAL_LIBRARY -> List.of(EXTERNAL_WARNING);
            case FEIGN_CLIENT -> List.of(FEIGN_WARNING);
            case SPRING_MULTIPLE_CANDIDATES -> List.of(SPRING_AMBIGUITY_WARNING);
            case UNRESOLVED_TARGET -> List.of(UNRESOLVED_WARNING);
            default -> List.of();
        };
    }

    private double confidence(ResolutionStrategy strategy) {
        return switch (strategy) {
            case UNRESOLVED_TARGET, SPRING_MULTIPLE_CANDIDATES -> 0.5;
            case BUSINESS_READ_FORBIDDEN -> 1.0;
            default -> 1.0;
        };
    }

    private CallTraversalState.EdgeKey edgeKey(
            CallNodeId caller, String targetIdentity, SemanticRange range) {
        return new CallTraversalState.EdgeKey(
                caller, targetIdentity,
                range.start().line(), range.start().character(), range.end().line(), range.end().character());
    }

    private SemanticRange semanticRange(SyntaxRange range) {
        return new SemanticRange(
                new SemanticPosition(range.start().line(), range.start().character()),
                new SemanticPosition(range.end().line(), range.end().character()));
    }

    private SemanticCallSite semanticCallSite(SyntaxInvocation invocation) {
        return new SemanticCallSite(
                semanticRange(invocation.range()),
                new SemanticPosition(
                        invocation.resolutionAnchor().line(), invocation.resolutionAnchor().character()));
    }

    private SyntaxRange syntaxRange(SemanticRange range) {
        return new SyntaxRange(
                new SyntaxPosition(range.start().line(), range.start().character()),
                new SyntaxPosition(range.end().line(), range.end().character()));
    }

    private record PendingCall(
            Optional<SemanticMethod> target,
            String rawSignature,
            SemanticRange callSite,
            boolean external,
            ResolutionStrategy strategy,
            SyntaxInvocation invocation,
            SemanticCallStatus semanticStatus,
            Optional<RuntimeException> failure) {

        private String targetPackage() {
            return target.map(SemanticMethod::packageName).orElse("~");
        }

        private String targetClass() {
            return target.map(SemanticMethod::className).orElse(rawSignature);
        }

        private String targetMethod() {
            return target.map(SemanticMethod::methodName).orElse(rawSignature);
        }

        private String targetParameters() {
            return target.map(method -> String.join(",", method.parameterTypes())).orElse("");
        }

        private String targetUri() {
            return target.map(method -> method.location().uri()).orElse("");
        }

        private int targetStartLine() {
            return target.map(method -> method.location().range().start().line()).orElse(-1);
        }

        private int targetStartCharacter() {
            return target.map(method -> method.location().range().start().character()).orElse(-1);
        }

        private int targetEndLine() {
            return target.map(method -> method.location().range().end().line()).orElse(-1);
        }

        private int targetEndCharacter() {
            return target.map(method -> method.location().range().end().character()).orElse(-1);
        }

        private PendingCall withTarget(SemanticMethod selected) {
            return new PendingCall(
                    Optional.of(selected), rawSignature, callSite, external, strategy, invocation,
                    SemanticCallStatus.RESOLVED, failure);
        }
    }

    private record CutoffEvidence(
            EvidenceEnricher.MethodEvidence methodEvidence,
            List<CallTraversalState.DefinitionFallbackFailure> definitionFallbackFailures,
            List<CallTraversalState.ConversionFailure> conversionFailures) {

        private CutoffEvidence {
            Objects.requireNonNull(methodEvidence, "methodEvidence is required");
            definitionFallbackFailures = List.copyOf(Objects.requireNonNull(
                    definitionFallbackFailures, "definitionFallbackFailures is required"));
            conversionFailures = List.copyOf(Objects.requireNonNull(
                    conversionFailures, "conversionFailures is required"));
        }
    }
}
