package com.java.semantic.callgraph.application;

import com.java.semantic.syntax.domain.SourceTypeKind;

import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticIncomingCall;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.incomingMethod;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.sourceClassification;
import static org.assertj.core.api.Assertions.assertThat;

class DirectionalCallGraphParityTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_match_the_outgoing_edge_for_the_selected_overload() {
        MethodTarget callerTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Caller"),
                        "Caller.java"),
                "run",
                List.of());
        MethodTarget stringTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Target"),
                        "Target.java"),
                "work",
                List.of("java.lang.String"));
        MethodTarget intTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Target"),
                        "Target.java"),
                "work",
                List.of("int"));
        SemanticMethod caller = incomingMethod(callerTarget, 0);
        SemanticMethod stringTargetMethod = incomingMethod(stringTarget, 10);
        SemanticRange callSite = new SemanticRange(new SemanticPosition(2, 0), new SemanticPosition(2, 4));
        FakeSemanticService semantic = new FakeSemanticService(caller, stringTargetMethod, callSite);
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(
                type(callerTarget, List.of(invocation(callSite))), type(stringTarget, List.of()), type(intTarget, List.of())));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment outgoing = new SemanticCallGraphBuilder(
                semantic, new SpringImplementationSelector())
                .build(SNAPSHOT, syntax, callerTarget, caller, 1, 0);
        com.java.semantic.callgraph.domain.IncomingGraphFragment incoming = new IncomingSemanticCallGraphBuilder(
                semantic, new DirectCallRelationshipResolver(
                        semantic, new SpringImplementationSelector(), new CanonicalTargetProjection()))
                .build(SNAPSHOT, syntax, stringTarget, stringTargetMethod, 1, 0);

        assertThat(normalize(outgoing.edges(), outgoing.nodes())).containsExactlyElementsOf(normalize(incoming.edges(), incoming.nodes()));
        assertThat(incoming.nodes()).filteredOn(node -> node.target().filter(intTarget::equals).isPresent()).hasSize(0);
    }

    private static List<String> normalize(List<GraphEdge> edges, List<com.java.semantic.callgraph.domain.GraphNode> nodes) {
        Map<com.java.semantic.callgraph.domain.CallNodeId, MethodTarget> targets = new HashMap<>();
        for (com.java.semantic.callgraph.domain.GraphNode node : nodes) {
            node.target().ifPresent(target -> targets.put(node.nodeId(), target));
        }
        return edges.stream().map(edge -> identity(targets.get(edge.callerNodeId()))
                        + "->" + identity(targets.get(edge.calleeNodeId()))
                        + "@" + edge.callSite().sourceFile() + ":" + edge.callSite().startLine())
                .toList();
    }

    private static String identity(MethodTarget target) {
        return target.sourceFile() + ":" + target.packageName() + "." + target.className()
                + "#" + target.methodName() + target.parameterTypes();
    }

    private static SourceTypeMetadata type(MethodTarget target, List<SyntaxInvocation> invocations) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(20, 0));
        SourceMethodMetadata method = new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), null, null, 1, 6,
                range, new SourceSlice(range, "void " + target.methodName() + "() {}"), List.<TypeReference>of(),
                Optional.empty(), invocations, List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), true, false, true);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(method), false, false, List.of(), range,
                new SourceSlice(range, "class " + target.className() + " {}"), false, List.of());
    }

    private static SyntaxInvocation invocation(SemanticRange range) {
        SyntaxRange syntaxRange = new SyntaxRange(
                new SyntaxPosition(range.start().line(), range.start().character()),
                new SyntaxPosition(range.end().line(), range.end().character()));
        return new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD, syntaxRange, "work(value)",
                "target", "", "", Optional.empty(), syntaxRange.start());
    }

    private static final class FakeSemanticService implements JavaSemanticService {

        @Override
        public SemanticSourceClassification classifySource(RepositorySnapshot snapshot, SemanticMethod method) {
            return sourceClassification(method);
        }

        @Override
        public List<SemanticReferenceLocation> findReferences(
                RepositorySnapshot snapshot, SemanticReferenceAnchor anchor) {
            return List.of();
        }

        private final SemanticMethod caller;
        private final SemanticMethod target;
        private final SemanticRange callSite;

        private FakeSemanticService(SemanticMethod caller, SemanticMethod target, SemanticRange callSite) {
            this.caller = caller;
            this.target = target;
            this.callSite = callSite;
        }

        @Override
        public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SemanticCallResolution resolveCallResolutionAt(
                RepositorySnapshot snapshot, SemanticMethod requestedCaller, SemanticCallSite requestedCallSite) {
            return SemanticCallResolution.resolved(call());
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod requestedCaller) {
            return caller.equals(requestedCaller) ? List.of(call()) : List.of();
        }

        @Override
        public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
            return target.equals(callee)
                    ? new SemanticIncomingCallResult(List.of(new SemanticIncomingCall(caller, "Caller.run()", List.of(callSite))), List.of())
                    : SemanticIncomingCallResult.empty();
        }

        @Override
        public SemanticImplementationResult implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            return new SemanticImplementationResult(List.of(), List.of());
        }

        private SemanticCall call() {
            return new SemanticCall(Optional.of(target), "Target.work(java.lang.String)", List.of(callSite), false,
                    SemanticResolutionOrigin.CALL_HIERARCHY);
        }
    }
}
