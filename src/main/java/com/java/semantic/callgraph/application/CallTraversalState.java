package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.CallNode;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Mutable state scoped to one deterministic depth-first graph build. */
final class CallTraversalState {

    private final Map<MethodId, CallNodeId> nodeIds = new LinkedHashMap<>();
    private final Map<MethodId, CallNodeId> restrictedNodeIds = new LinkedHashMap<>();
    private final List<CallNode> nodes = new ArrayList<>();
    private final List<CallEdge> edges = new ArrayList<>();
    private final List<AnalysisWarning> warnings = new ArrayList<>();
    private final List<AnalysisError> errors = new ArrayList<>();
    private final Set<MethodId> currentPath = new HashSet<>();
    private final Map<EdgeKey, Integer> edgeIndexes = new HashMap<>();
    private final Map<MethodId, CutoffEvidenceOutcome> cutoffEvidenceOutcomes = new HashMap<>();
    private int nextNodeOrdinal = 1;
    private int nextRestrictedOrdinal = 1;

    CallNodeId readableNode(MethodId methodId, NodeFactory factory) {
        CallNodeId existing = nodeIds.get(methodId);
        if (Objects.nonNull(existing)) {
            return existing;
        }
        CallNodeId created = nextNodeId();
        nodeIds.put(methodId, created);
        addNode(factory.create(created));
        return created;
    }

    Optional<CallNodeId> readableNodeId(MethodId methodId) {
        return Optional.ofNullable(nodeIds.get(methodId));
    }

    CallNodeId occurrenceNode(NodeFactory factory) {
        CallNodeId created = nextNodeId();
        addNode(factory.create(created));
        return created;
    }

    CallNodeId restrictedNode(MethodId methodId, NodeFactory factory) {
        CallNodeId existing = restrictedNodeIds.get(methodId);
        if (Objects.nonNull(existing)) {
            return existing;
        }
        CallNodeId created = new CallNodeId("restricted-node-" + nextRestrictedOrdinal++);
        restrictedNodeIds.put(methodId, created);
        addNode(factory.create(created));
        return created;
    }

    boolean enter(MethodId methodId) {
        return currentPath.add(methodId);
    }

    void leave(MethodId methodId) {
        currentPath.remove(methodId);
    }

    boolean onCurrentPath(MethodId methodId) {
        return currentPath.contains(methodId);
    }

    boolean addEdge(CallEdge edge, EdgeKey key) {
        if (edgeIndexes.containsKey(key)) {
            return false;
        }
        edgeIndexes.put(key, edges.size());
        edges.add(edge);
        return true;
    }

    OccurrenceCheckpoint checkpoint(
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence) {
        return new OccurrenceCheckpoint(
                new LinkedHashMap<>(nodeIds),
                new LinkedHashMap<>(restrictedNodeIds),
                List.copyOf(nodes),
                List.copyOf(edges),
                List.copyOf(warnings),
                List.copyOf(errors),
                new HashMap<>(edgeIndexes),
                new HashMap<>(cutoffEvidenceOutcomes),
                new HashMap<>(methodEvidence),
                nextNodeOrdinal,
                nextRestrictedOrdinal);
    }

    void rollback(OccurrenceCheckpoint checkpoint) {
        nodeIds.clear();
        nodeIds.putAll(checkpoint.nodeIds());
        restrictedNodeIds.clear();
        restrictedNodeIds.putAll(checkpoint.restrictedNodeIds());
        nodes.clear();
        nodes.addAll(checkpoint.nodes());
        edges.clear();
        edges.addAll(checkpoint.edges());
        warnings.clear();
        warnings.addAll(checkpoint.warnings());
        errors.clear();
        errors.addAll(checkpoint.errors());
        edgeIndexes.clear();
        edgeIndexes.putAll(checkpoint.edgeIndexes());
        cutoffEvidenceOutcomes.clear();
        cutoffEvidenceOutcomes.putAll(checkpoint.cutoffEvidenceOutcomes());
        nextNodeOrdinal = checkpoint.nextNodeOrdinal();
        nextRestrictedOrdinal = checkpoint.nextRestrictedOrdinal();
    }

    boolean containsEdge(EdgeKey key) {
        return edgeIndexes.containsKey(key);
    }

    Optional<CutoffEvidenceOutcome> cutoffEvidenceOutcome(MethodId methodId) {
        return Optional.ofNullable(cutoffEvidenceOutcomes.get(methodId));
    }

    void recordCutoffEvidenceOutcome(MethodId methodId, CutoffEvidenceOutcome outcome) {
        cutoffEvidenceOutcomes.put(
                Objects.requireNonNull(methodId, "methodId is required"),
                Objects.requireNonNull(outcome, "outcome is required"));
    }

    void addWarning(AnalysisWarning warning) {
        warnings.add(warning);
    }

    void addError(AnalysisError error) {
        errors.add(error);
    }

    List<CallNode> nodes() {
        return List.copyOf(nodes);
    }

    List<CallEdge> edges() {
        return List.copyOf(edges);
    }

    List<AnalysisWarning> warnings() {
        return List.copyOf(warnings);
    }

    List<AnalysisError> errors() {
        return List.copyOf(errors);
    }

    private CallNodeId nextNodeId() {
        return new CallNodeId("node-" + nextNodeOrdinal++);
    }

    private void addNode(CallNode node) {
        nodes.add(node);
    }

    @FunctionalInterface
    interface NodeFactory {
        CallNode create(CallNodeId nodeId);
    }

    record EdgeKey(CallNodeId caller, String targetIdentity, int startLine, int startCharacter,
                   int endLine, int endCharacter) {
    }

    record OccurrenceCheckpoint(
            Map<MethodId, CallNodeId> nodeIds,
            Map<MethodId, CallNodeId> restrictedNodeIds,
            List<CallNode> nodes,
            List<CallEdge> edges,
            List<AnalysisWarning> warnings,
            List<AnalysisError> errors,
            Map<EdgeKey, Integer> edgeIndexes,
            Map<MethodId, CutoffEvidenceOutcome> cutoffEvidenceOutcomes,
            Map<MethodId, EvidenceEnricher.MethodEvidence> methodEvidence,
            int nextNodeOrdinal,
            int nextRestrictedOrdinal) {
    }

    record CutoffEvidenceOutcome(
            Optional<EvidenceEnricher.MethodEvidence> evidence,
            Optional<AnalysisError> error,
            List<DefinitionFallbackFailure> definitionFallbackFailures,
            List<ConversionFailure> conversionFailures) {

        CutoffEvidenceOutcome {
            evidence = Objects.requireNonNull(evidence, "evidence is required");
            error = Objects.requireNonNull(error, "error is required");
            definitionFallbackFailures = List.copyOf(Objects.requireNonNull(
                    definitionFallbackFailures, "definitionFallbackFailures is required"));
            conversionFailures = List.copyOf(Objects.requireNonNull(
                    conversionFailures, "conversionFailures is required"));
            if (evidence.isPresent() == error.isPresent()) {
                throw new IllegalArgumentException("exactly one cutoff outcome is required");
            }
        }

        static CutoffEvidenceOutcome success(
                EvidenceEnricher.MethodEvidence evidence,
                List<DefinitionFallbackFailure> definitionFallbackFailures,
                List<ConversionFailure> conversionFailures) {
            return new CutoffEvidenceOutcome(
                    Optional.of(evidence), Optional.empty(), definitionFallbackFailures, conversionFailures);
        }

        static CutoffEvidenceOutcome failure(AnalysisError error) {
            return new CutoffEvidenceOutcome(Optional.empty(), Optional.of(error), List.of(), List.of());
        }
    }

    record DefinitionFallbackFailure(
            String rawSignature,
            SemanticRange callSite,
            SyntaxInvocation invocation,
            RuntimeException exception) {

        DefinitionFallbackFailure {
            Objects.requireNonNull(rawSignature, "rawSignature is required");
            Objects.requireNonNull(callSite, "callSite is required");
            Objects.requireNonNull(invocation, "invocation is required");
            Objects.requireNonNull(exception, "exception is required");
        }
    }

    record ConversionFailure(
            String rawSignature,
            SemanticRange callSite,
            SyntaxInvocation invocation) {

        ConversionFailure {
            Objects.requireNonNull(rawSignature, "rawSignature is required");
            Objects.requireNonNull(callSite, "callSite is required");
            Objects.requireNonNull(invocation, "invocation is required");
        }
    }
}
