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
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.identity.PolicyIdentity;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** 補充可讀圖證據，且不複製業務禁止讀取的內容 */
public final class EvidenceEnricher {

    static final String NO_SQL_WARNING =
            "Data access target has no readable SQL evidence; do not infer query behavior";

    private final ReadPolicy readPolicy;

    public EvidenceEnricher(ReadPolicy readPolicy) {
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
    }

    public EnrichmentResult enrich(
            ExplainableCallGraph graph,
            RepositorySyntaxIndex index,
            Map<MethodId, MethodEvidence> methodEvidence) {
        Objects.requireNonNull(graph, "graph is required");
        Objects.requireNonNull(index, "index is required");
        Objects.requireNonNull(methodEvidence, "methodEvidence is required");

        Map<CallNodeId, MethodSignature> syntaxMethods = syntaxMethods(graph, index);
        List<CallNode> nodes = graph.nodes().stream()
                .map(node -> enrichNode(
                        node,
                        syntaxMethods.get(node.nodeId()),
                        methodEvidence.get(node.methodId()),
                        index,
                        graph.root().repoId()))
                .toList();
        List<CallEdge> edges = graph.edges().stream()
                .map(edge -> enrichEdge(edge, nodes, syntaxMethods))
                .toList();
        RelatedClasses relatedClasses = relatedClasses(
                nodes, syntaxMethods, index, graph.root().repoId());
        ExplainableCallGraph enriched = new ExplainableCallGraph(
                graph.root(), nodes, edges, relatedClasses.sources(),
                flattened(graph.root(), nodes, edges, relatedClasses.sources()));
        return new EnrichmentResult(enriched, relatedClasses.evidence());
    }

    private Map<CallNodeId, MethodSignature> syntaxMethods(
            ExplainableCallGraph graph, RepositorySyntaxIndex index) {
        Map<CallNodeId, MethodSignature> methods = new LinkedHashMap<>();
        for (CallNode node : graph.nodes()) {
            if (Objects.nonNull(node.methodId())) {
                index.methods(node.methodId()).stream().findFirst()
                        .ifPresent(method -> methods.put(node.nodeId(), method));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(methods));
    }

    private CallNode enrichNode(
            CallNode node,
            MethodSignature method,
            MethodEvidence evidence,
            RepositorySyntaxIndex index,
            String repoId) {
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility())) {
            return node;
        }
        boolean containsForbiddenReference = Objects.nonNull(evidence) && evidence.containsForbiddenReference();
        if (Objects.nonNull(method)) {
            containsForbiddenReference = containsForbiddenReference
                    || containsForbiddenReference(method, index, repoId);
        }
        if (containsForbiddenReference) {
            return new CallNode(
                    node.nodeId(), node.methodId(), node.signature(), node.callType(), node.sourceFile(),
                    node.startLine(), node.endLine(), Map.of(), "", node.visibility());
        }
        String code = node.code();
        Map<String, String> annotations = node.annotations();
        if (Objects.nonNull(method)) {
            annotations = annotations(method);
            if (CallType.UNRESOLVED.equals(node.callType())) {
                code = "";
                annotations = Map.of();
            } else if (CallType.DATA_ACCESS.equals(node.callType()) && Objects.nonNull(method.sql())) {
                code = method.sql();
            } else if (CallType.TRAVERSAL_CUTOFF.equals(node.callType())) {
                code = cutoffCode(method, evidence);
            } else if (CallType.GENERATED_CODE.equals(node.callType())) {
                code = "";
                Map<String, String> generated = new LinkedHashMap<>(annotations);
                generated.put("generatedEvidence",
                        "Lombok generated method " + node.signature() + "; no source body exists");
                annotations = Collections.unmodifiableMap(new LinkedHashMap<>(generated));
            } else {
                code = method.source().text();
            }
        }
        return new CallNode(
                node.nodeId(), node.methodId(), node.signature(), node.callType(), node.sourceFile(),
                node.startLine(), node.endLine(), annotations, code, node.visibility());
    }

    private boolean containsForbiddenReference(
            MethodSignature method,
            RepositorySyntaxIndex index,
            String repoId) {
        List<TypeReference> references = new ArrayList<>(method.parameterTypeReferences());
        method.returnType().ifPresent(references::add);
        return containsForbiddenAnnotation(method.annotationEvidence(), repoId)
                || containsForbiddenTypeIdentities(method.bodyTypeReferences(), repoId)
                || containsForbiddenInvocation(method.invocations(), repoId)
                || references.stream()
                .anyMatch(reference -> containsForbiddenReference(
                        reference, index, repoId, new HashSet<>()));
    }

    private boolean containsForbiddenAnnotation(List<AnnotationEvidence> annotations, String repoId) {
        return annotations.stream()
                .flatMap(annotation -> annotation.resolvedType().stream())
                .map(type -> new TypeId(repoId, type.packageName(),
                        PolicyIdentity.className(type.packageName(), type.className())))
                .anyMatch(type -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(type)));
    }

    private boolean containsForbiddenTypeIdentities(List<ResolvedTypeIdentity> references, String repoId) {
        return references.stream()
                .map(type -> new TypeId(repoId, type.packageName(),
                        PolicyIdentity.className(type.packageName(), type.className())))
                .anyMatch(type -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(type)));
    }

    private boolean containsForbiddenInvocationTargets(List<InvocationTarget> targets, String repoId) {
        return targets.stream().anyMatch(target -> invocationTargetIsForbidden(target, repoId));
    }

    private boolean invocationTargetIsForbidden(InvocationTarget target, String repoId) {
        String className = PolicyIdentity.className(target.packageName(), target.className());
        TypeId typeId = new TypeId(repoId, target.packageName(), className);
        MethodId methodId = new MethodId(
                repoId,
                target.packageName(),
                className,
                target.methodName(),
                PolicyIdentity.parameterTypes(target.parameterTypes()));
        return EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(typeId))
                || EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(methodId));
    }

    private boolean containsForbiddenInvocation(List<SyntaxInvocation> invocations, String repoId) {
        return containsForbiddenInvocationTargets(invocations.stream()
                .map(SyntaxInvocation::resolvedTarget)
                .flatMap(Optional::stream)
                .toList(), repoId);
    }

    private String cutoffCode(MethodSignature method, MethodEvidence evidence) {
        if (Objects.nonNull(evidence) && evidence.containsForbiddenReference()) {
            return "";
        }
        List<String> signatures = Objects.isNull(evidence)
                ? List.of()
                : evidence.immediateCalleeSignatures().stream().distinct().sorted().toList();
        if (CollectionUtils.isEmpty(signatures)) {
            return method.source().text();
        }
        return method.source().text() + "\n\nImmediate callees:\n" + String.join("\n", signatures);
    }

    private Map<String, String> annotations(MethodSignature method) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < method.annotations().size(); index++) {
            values.put("annotation-" + (index + 1), method.annotations().get(index));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private CallEdge enrichEdge(
            CallEdge edge,
            List<CallNode> nodes,
            Map<CallNodeId, MethodSignature> syntaxMethods) {
        Optional<CallNode> callee = nodes.stream()
                .filter(node -> node.nodeId().equals(edge.callee()))
                .findFirst();
        if (!callee.isPresent() || !CallType.DATA_ACCESS.equals(callee.orElseThrow().callType())) {
            return edge;
        }
        MethodSignature method = syntaxMethods.get(edge.callee());
        boolean hasSql = Objects.nonNull(method) && Objects.nonNull(method.sql());
        if (hasSql) {
            return copyEdge(edge, ResolutionStrategy.MYBATIS_MAPPER, edge.warnings());
        }
        return copyEdge(edge, ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, List.of(NO_SQL_WARNING));
    }

    private CallEdge copyEdge(CallEdge edge, ResolutionStrategy strategy, List<String> warnings) {
        return new CallEdge(
                edge.caller(), edge.callee(), edge.callExpression(), edge.callSite(),
                strategy, edge.confidence(), edge.evidence(), warnings, edge.visibility());
    }

    private RelatedClasses relatedClasses(
            List<CallNode> nodes,
            Map<CallNodeId, MethodSignature> syntaxMethods,
            RepositorySyntaxIndex index,
            String repoId) {
        Map<String, String> slices = new TreeMap<>();
        Set<String> visited = new HashSet<>();
        for (CallNode node : nodes) {
            if (!EvidenceVisibility.READABLE.equals(node.visibility())) {
                continue;
            }
            MethodSignature method = syntaxMethods.get(node.nodeId());
            if (Objects.isNull(method)) {
                continue;
            }
            List<TypeReference> roots = new ArrayList<>(method.parameterTypeReferences());
            method.returnType().ifPresent(roots::add);
            roots.stream()
                    .sorted(Comparator.comparing(TypeReference::resolvedType)
                            .thenComparing(TypeReference::writtenType))
                    .forEach(reference -> collectRelated(reference, index, repoId, visited, slices));
        }
        List<RelatedClassEvidence> evidence = new ArrayList<>();
        for (String canonicalName : slices.keySet()) {
            index.classes(canonicalName).stream().findFirst().ifPresent(metadata -> evidence.add(
                            new RelatedClassEvidence(
                            new TypeId(repoId, metadata.packageName(),
                                    PolicyIdentity.className(metadata.packageName(), metadata.className())),
                            slices.get(canonicalName))));
        }
        return new RelatedClasses(
                Collections.unmodifiableMap(new LinkedHashMap<>(slices)),
                List.copyOf(evidence));
    }

    private void collectRelated(
            TypeReference reference,
            RepositorySyntaxIndex index,
            String repoId,
            Set<String> visited,
            Map<String, String> slices) {
        nestedReferences(reference).stream()
                .forEach(argument -> collectRelated(argument, index, repoId, visited, slices));
        if (!reference.sourceDefined() || !visited.add(reference.resolvedType())) {
            return;
        }
        Optional<ClassMetadata> metadata = index.classes(reference.resolvedType()).stream().findFirst();
        if (!metadata.isPresent()
                || containsForbiddenReference(metadata.orElseThrow(), index, repoId, new HashSet<>())) {
            return;
        }
        ClassMetadata readable = metadata.orElseThrow();
        slices.put(readable.fullyQualifiedName(), readable.source().text());
        readable.fields().stream()
                .map(ClassMetadata.FieldInfo::typeReference)
                .sorted(typeReferenceOrder())
                .forEach(field -> collectRelated(field, index, repoId, visited, slices));
    }

    private boolean containsForbiddenReference(
            ClassMetadata metadata,
            RepositorySyntaxIndex index,
            String repoId,
            Set<String> visited) {
        if (!visited.add(metadata.fullyQualifiedName()) || forbidden(metadata, repoId)) {
            return forbidden(metadata, repoId);
        }
        return containsForbiddenAnnotation(metadata.annotationEvidence(), repoId)
                || metadata.fields().stream().anyMatch(field ->
                containsForbiddenAnnotation(field.annotationEvidence(), repoId)
                        || containsForbiddenReference(field.typeReference(), index, repoId, visited))
                || metadata.methods().stream()
                .anyMatch(method -> containsForbiddenReference(method, index, repoId));
    }

    private boolean containsForbiddenReference(
            TypeReference reference,
            RepositorySyntaxIndex index,
            String repoId,
            Set<String> visited) {
        if (nestedReferences(reference).stream()
                .anyMatch(argument -> containsForbiddenReference(argument, index, repoId, visited))) {
            return true;
        }
        if (!reference.sourceDefined()) {
            return false;
        }
        return index.classes(reference.resolvedType()).stream().findFirst()
                .map(metadata -> containsForbiddenReference(metadata, index, repoId, visited))
                .orElse(false);
    }

    private boolean forbidden(ClassMetadata metadata, String repoId) {
        TypeId typeId = new TypeId(repoId, metadata.packageName(),
                PolicyIdentity.className(metadata.packageName(), metadata.className()));
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(typeId))) {
            return true;
        }
        return metadata.methods().stream()
                .map(method -> new MethodId(
                        repoId,
                        metadata.packageName(),
                        PolicyIdentity.className(metadata.packageName(), metadata.className()),
                        method.name(),
                        PolicyIdentity.parameterTypes(method.paramTypes())))
                .anyMatch(methodId -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                        readPolicy.visibilityOf(methodId)));
    }

    private List<TypeReference> nestedReferences(TypeReference reference) {
        List<TypeReference> references = new ArrayList<>();
        references.addAll(reference.typeArguments());
        references.addAll(reference.upperBounds());
        references.addAll(reference.lowerBounds());
        return references.stream().sorted(typeReferenceOrder()).toList();
    }

    private Comparator<TypeReference> typeReferenceOrder() {
        return Comparator.comparing(TypeReference::resolvedType)
                .thenComparing(TypeReference::writtenType)
                .thenComparing(reference -> reference.upperBounds().toString())
                .thenComparing(reference -> reference.lowerBounds().toString());
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

    public record MethodEvidence(List<String> immediateCalleeSignatures, boolean containsForbiddenReference) {
        public MethodEvidence {
            immediateCalleeSignatures = List.copyOf(Objects.requireNonNull(
                    immediateCalleeSignatures, "immediateCalleeSignatures is required"));
        }
    }

    public record EnrichmentResult(ExplainableCallGraph graph, List<RelatedClassEvidence> relatedClassEvidence) {
        public EnrichmentResult {
            Objects.requireNonNull(graph, "graph is required");
            relatedClassEvidence = List.copyOf(Objects.requireNonNull(
                    relatedClassEvidence, "relatedClassEvidence is required"));
        }
    }

    private record RelatedClasses(Map<String, String> sources, List<RelatedClassEvidence> evidence) {
    }
}
