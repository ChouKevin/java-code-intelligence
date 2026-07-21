package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallEdge;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 建立公開圖檢視，並產生不含內容且具決定性的受限關係 */
public final class RestrictedEvidenceRedactor {

    public static final String GENERIC_WARNING =
            "Business policy prohibits reading or summarizing this target";

    private final ReadPolicy readPolicy;

    public RestrictedEvidenceRedactor(ReadPolicy readPolicy) {
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
    }

    public ExplainableCallGraph redact(
            ExplainableCallGraph graph,
            List<RelatedClassEvidence> relatedClassEvidence) {
        Objects.requireNonNull(graph, "graph is required");
        List<RelatedClassEvidence> immutableRelatedClassEvidence = List.copyOf(Objects.requireNonNull(
                relatedClassEvidence, "relatedClassEvidence is required"));
        Map<CallNodeId, CallNodeId> nodeIds = new LinkedHashMap<>();
        List<CallNode> nodes = new ArrayList<>();
        int restrictedOrdinal = 1;
        for (CallNode node : graph.nodes()) {
            if (forbidden(node)) {
                CallNodeId restrictedId = new CallNodeId("restricted-node-" + restrictedOrdinal++);
                nodeIds.put(node.nodeId(), restrictedId);
                nodes.add(restrictedNode(restrictedId));
            } else {
                nodeIds.put(node.nodeId(), node.nodeId());
                nodes.add(node);
            }
        }
        List<CallEdge> edges = graph.edges().stream()
                .map(edge -> redactEdge(edge, nodeIds, nodes))
                .toList();
        List<CallNode> safeNodes = nodes.stream()
                .map(node -> omitCodeWithForbiddenReference(node, edges))
                .toList();
        Map<String, String> relatedClasses = redactRelatedClasses(
                graph.root(), graph.relatedClasses(), immutableRelatedClassEvidence);
        return new ExplainableCallGraph(
                graph.root(), safeNodes, edges, relatedClasses,
                flattened(graph.root(), safeNodes, edges, relatedClasses));
    }

    private boolean forbidden(CallNode node) {
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility())) {
            return true;
        }
        return Objects.nonNull(node.methodId())
                && EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(node.methodId()));
    }

    private CallNode restrictedNode(CallNodeId nodeId) {
        return new CallNode(
                nodeId, null, "", CallType.UNRESOLVED, "", null, null,
                Map.of(), "", EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
    }

    private CallEdge redactEdge(
            CallEdge edge, Map<CallNodeId, CallNodeId> nodeIds, List<CallNode> nodes) {
        CallNodeId caller = Objects.requireNonNull(nodeIds.get(edge.caller()), "caller node is required");
        CallNodeId callee = Objects.requireNonNull(nodeIds.get(edge.callee()), "callee node is required");
        boolean forbidden = nodes.stream()
                .filter(node -> node.nodeId().equals(caller) || node.nodeId().equals(callee))
                .anyMatch(node -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility()));
        if (forbidden) {
            return new CallEdge(
                    caller, callee, "", null, ResolutionStrategy.BUSINESS_READ_FORBIDDEN, 1.0,
                    List.of(), List.of(GENERIC_WARNING), EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        }
        return new CallEdge(
                caller, callee, edge.callExpression(), edge.callSite(),
                edge.resolutionStrategy(), edge.confidence(), edge.evidence(), edge.warnings(), edge.visibility());
    }

    private CallNode omitCodeWithForbiddenReference(CallNode node, List<CallEdge> edges) {
        if (!EvidenceVisibility.READABLE.equals(node.visibility())) {
            return node;
        }
        boolean containsForbiddenReference = edges.stream()
                .filter(edge -> edge.caller().equals(node.nodeId()))
                .anyMatch(edge -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(edge.visibility()));
        if (!containsForbiddenReference) {
            return node;
        }
        return new CallNode(
                node.nodeId(), node.methodId(), node.signature(), node.callType(), node.sourceFile(),
                node.startLine(), node.endLine(), Map.of(), "", node.visibility());
    }

    private Map<String, String> redactRelatedClasses(
            MethodId root,
            Map<String, String> relatedClasses,
            List<RelatedClassEvidence> relatedClassEvidence) {
        Map<String, RelatedClassEvidence> evidenceByCanonicalName = new LinkedHashMap<>();
        for (RelatedClassEvidence evidence : relatedClassEvidence) {
            evidenceByCanonicalName.put(evidence.canonicalName(), evidence);
        }
        Map<String, String> safe = new LinkedHashMap<>();
        relatedClasses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> matchingReadableEvidence(root, entry, evidenceByCanonicalName).isPresent())
                .forEach(entry -> safe.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(safe));
    }

    private Optional<RelatedClassEvidence> matchingReadableEvidence(
            MethodId root,
            Map.Entry<String, String> source,
            Map<String, RelatedClassEvidence> evidenceByCanonicalName) {
        return Optional.ofNullable(evidenceByCanonicalName.get(source.getKey()))
                .filter(evidence -> root.repoId().equals(evidence.typeId().repoId()))
                .filter(evidence -> evidence.canonicalName().equals(source.getKey()))
                .filter(evidence -> evidence.source().equals(source.getValue()))
                .filter(evidence -> !forbiddenType(evidence.typeId()));
    }

    private boolean forbiddenType(TypeId typeId) {
        return Objects.isNull(typeId)
                || EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(typeId));
    }

    private FlattenedCallGraph flattened(
            MethodId root,
            List<CallNode> nodes,
            List<CallEdge> edges,
            Map<String, String> relatedClasses) {
        List<FlattenedMethodNode> methods = nodes.stream()
                .filter(node -> EvidenceVisibility.READABLE.equals(node.visibility()))
                .map(node -> new FlattenedMethodNode(
                        node.signature(), Optional.ofNullable(node.methodId()).map(MethodId::className).orElse(""),
                        Optional.ofNullable(node.methodId()).map(MethodId::methodName).orElse(""), node.callType(),
                        "", node.code(), node.annotations(), callees(node, nodes, edges)))
                .toList();
        String rootSignature = root.methodName() + "(" + String.join(",", root.parameterTypes()) + ")";
        return new FlattenedCallGraph(methods, rootSignature, relatedClasses);
    }

    private List<String> callees(CallNode node, List<CallNode> nodes, List<CallEdge> edges) {
        return edges.stream()
                .filter(edge -> edge.caller().equals(node.nodeId()))
                .filter(edge -> EvidenceVisibility.READABLE.equals(edge.visibility()))
                .map(CallEdge::callee)
                .map(callee -> nodes.stream()
                        .filter(candidate -> candidate.nodeId().equals(callee))
                        .findFirst())
                .flatMap(Optional::stream)
                .filter(candidate -> EvidenceVisibility.READABLE.equals(candidate.visibility()))
                .map(CallNode::signature)
                .toList();
    }
}
