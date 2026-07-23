package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticIncomingCall;
import com.java.semantic.semantic.domain.SemanticIncomingCallIssue;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.incomingMethod;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.target;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncomingSemanticCallGraphBuilderTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_build_caller_to_callee_edges_for_each_distinct_call_site_with_full_bodies() {
        MethodTarget calleeTarget = target("Callee", "work");
        MethodTarget callerTarget = target("Caller", "run");
        SemanticMethod callee = incomingMethod(calleeTarget, 10);
        SemanticMethod caller = incomingMethod(callerTarget, 0);
        SemanticRange first = semanticRange(2);
        SemanticRange second = semanticRange(4);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(callee, incoming(caller, second, first, first))
                .resolution(caller, first, localCall(callee, first))
                .resolution(caller, second, localCall(callee, second));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(calleeTarget, callerTarget), calleeTarget, callee, 1, 0);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.edges()).hasSize(2);
        assertThat(fragment.edges()).extracting(edge -> edge.callSite().startLine()).containsExactly(2, 4);
        assertThat(fragment.nodes()).allSatisfy(node -> {
            assertThat(node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
            assertThat(node.methodBody()).isPresent();
        });
    }

    @Test
    void should_not_guess_forward_mismatches_external_unresolved_or_ambiguous_callers() {
        MethodTarget calleeTarget = target("Callee", "work");
        MethodTarget mismatchTarget = target("Mismatch", "work");
        MethodTarget firstCallerTarget = target("FirstCaller", "run");
        MethodTarget secondCallerTarget = target("SecondCaller", "run");
        MethodTarget thirdCallerTarget = target("ThirdCaller", "run");
        MethodTarget fourthCallerTarget = target("FourthCaller", "run");
        MethodTarget alphaTarget = target("Alpha", "work");
        MethodTarget zetaTarget = target("Zeta", "work");
        SemanticMethod callee = incomingMethod(calleeTarget, 20);
        SemanticMethod firstCaller = incomingMethod(firstCallerTarget, 0);
        SemanticMethod secondCaller = incomingMethod(secondCallerTarget, 2);
        SemanticMethod thirdCaller = incomingMethod(thirdCallerTarget, 4);
        SemanticMethod fourthCaller = incomingMethod(fourthCallerTarget, 6);
        SemanticRange first = semanticRange(1);
        SemanticRange second = semanticRange(3);
        SemanticRange third = semanticRange(5);
        SemanticRange fourth = semanticRange(7);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(callee,
                        incoming(firstCaller, first), incoming(secondCaller, second),
                        incoming(thirdCaller, third), incoming(fourthCaller, fourth))
                .resolution(firstCaller, first, localCall(incomingMethod(mismatchTarget, 30), first))
                .resolution(secondCaller, second, externalCall(second))
                .resolution(thirdCaller, third, SemanticCallResolution.unresolved())
                .resolution(fourthCaller, fourth,
                        SemanticCallResolution.ambiguous(List.of(incomingMethod(zetaTarget, 40), incomingMethod(alphaTarget, 50))));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT,
                        syntax(calleeTarget, mismatchTarget, firstCallerTarget, secondCallerTarget, thirdCallerTarget,
                                fourthCallerTarget, alphaTarget, zetaTarget),
                        calleeTarget, callee, 1, 0);

        assertThat(fragment.edges()).hasSize(0);
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_AMBIGUOUS", "DESCENDANT_CALL_UNRESOLVED",
                        "DESCENDANT_CALL_UNRESOLVED", "DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.warnings()).filteredOn(warning -> "DESCENDANT_CALL_AMBIGUOUS".equals(warning.code()))
                .singleElement().extracting(warning -> warning.candidates()).isEqualTo(List.of(alphaTarget, zetaTarget));
    }

    @Test
    void should_not_expand_root_callers_without_an_exact_forward_validated_relationship() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget rejectedTarget = target("Rejected", "work");
        MethodTarget mismatchTarget = target("Mismatch", "work");
        MethodTarget alphaTarget = target("Alpha", "save");
        MethodTarget ghostTarget = target("Ghost", "save");
        MethodTarget zetaTarget = target("Zeta", "save");
        SemanticMethod root = incomingMethod(rootTarget, 20);
        SemanticMethod direct = incomingMethod(directTarget, 10);
        SemanticMethod rejected = incomingMethod(rejectedTarget, 8);
        SemanticMethod alpha = incomingMethod(alphaTarget, 0);
        SemanticMethod ghost = incomingMethod(ghostTarget, 2);
        SemanticRange directSite = semanticRange(1);
        SemanticRange mismatchSite = semanticRange(2);
        SemanticRange ambiguousSite = semanticRange(3);
        SemanticRange unresolvedSite = semanticRange(4);
        SemanticRange alphaSite = semanticRange(5);
        SemanticRange ghostSite = semanticRange(7);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(root,
                        incoming(rejected, unresolvedSite, ambiguousSite, mismatchSite),
                        incoming(direct, directSite))
                .incoming(direct, incoming(alpha, alphaSite))
                .incoming(rejected, incoming(ghost, ghostSite))
                .resolution(direct, directSite, localCall(root, directSite))
                .resolution(rejected, mismatchSite, localCall(incomingMethod(mismatchTarget, 30), mismatchSite))
                .resolution(rejected, ambiguousSite,
                        SemanticCallResolution.ambiguous(List.of(incomingMethod(zetaTarget, 40), incomingMethod(alphaTarget, 50))))
                .resolution(rejected, unresolvedSite, SemanticCallResolution.unresolved())
                .resolution(alpha, alphaSite, localCall(direct, alphaSite))
                .resolution(ghost, ghostSite, localCall(rejected, ghostSite));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT,
                        syntax(rootTarget, directTarget, rejectedTarget, mismatchTarget, alphaTarget, ghostTarget, zetaTarget),
                        rootTarget, root, 2, 1);

        assertThat(semantic.incomingQueries()).containsExactly(root, direct);
        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.edges()).hasSize(2);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(rejectedTarget::equals).isPresent()).hasSize(0);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(ghostTarget::equals).isPresent()).hasSize(0);
        assertThat(fragment.traversal().expandedNodeCount()).isEqualTo(1);
        assertThat(fragment.traversal().limitReason()).isEqualTo(GraphLimitReason.NONE);
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_AMBIGUOUS", "DESCENDANT_CALL_UNRESOLVED", "DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.warnings()).filteredOn(warning -> "DESCENDANT_CALL_AMBIGUOUS".equals(warning.code()))
                .singleElement().extracting(warning -> warning.candidates()).isEqualTo(List.of(alphaTarget, zetaTarget));
    }

    @Test
    void should_mark_descendant_only_incoming_rejections_as_partial() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        SemanticMethod root = incomingMethod(rootTarget, 10);
        SemanticMethod direct = incomingMethod(directTarget, 0);
        SemanticRange directSite = semanticRange(2);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(root, incoming(direct, directSite))
                .incomingResult(direct, new SemanticIncomingCallResult(
                        List.of(), List.of(SemanticIncomingCallIssue.callerRejected())))
                .resolution(direct, directSite, localCall(root, directSite));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget), rootTarget, root, 2, 1);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("INCOMING_CALLER_REJECTED");
            assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(directTarget::equals).isPresent())
                    .singleElement().extracting(node -> node.nodeId()).isEqualTo(warning.nodeId());
        });
    }

    @Test
    void should_produce_equal_fragments_for_permuted_incoming_callers_ranges_and_issues() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget rejectedTarget = target("Rejected", "work");
        MethodTarget mismatchTarget = target("Mismatch", "work");
        SemanticMethod root = incomingMethod(rootTarget, 20);
        SemanticMethod direct = incomingMethod(directTarget, 10);
        SemanticMethod rejected = incomingMethod(rejectedTarget, 8);
        SemanticRange exactSite = semanticRange(1);
        SemanticRange mismatchSite = semanticRange(2);
        SemanticRange unresolvedSite = semanticRange(4);
        SemanticIncomingCallIssue firstIssue = new SemanticIncomingCallIssue("CALLER_REJECTED", "first rejection");
        SemanticIncomingCallIssue secondIssue = new SemanticIncomingCallIssue("CALLER_REJECTED", "second rejection");
        SemanticIncomingCallResult firstResult = new SemanticIncomingCallResult(
                List.of(incoming(rejected, unresolvedSite, mismatchSite), incoming(direct, exactSite)),
                List.of(firstIssue, secondIssue));
        SemanticIncomingCallResult secondResult = new SemanticIncomingCallResult(
                List.of(incoming(direct, exactSite), incoming(rejected, mismatchSite, unresolvedSite)),
                List.of(secondIssue, firstIssue));
        FakeSemanticService firstSemantic = new FakeSemanticService()
                .incomingResult(root, firstResult)
                .resolution(direct, exactSite, localCall(root, exactSite))
                .resolution(rejected, mismatchSite, localCall(incomingMethod(mismatchTarget, 30), mismatchSite))
                .resolution(rejected, unresolvedSite, SemanticCallResolution.unresolved());
        FakeSemanticService secondSemantic = new FakeSemanticService()
                .incomingResult(root, secondResult)
                .resolution(direct, exactSite, localCall(root, exactSite))
                .resolution(rejected, mismatchSite, localCall(incomingMethod(mismatchTarget, 30), mismatchSite))
                .resolution(rejected, unresolvedSite, SemanticCallResolution.unresolved());

        IncomingGraphFragment first = builder(firstSemantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, rejectedTarget, mismatchTarget), rootTarget, root, 1, 0);
        IncomingGraphFragment second = builder(secondSemantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, rejectedTarget, mismatchTarget), rootTarget, root, 1, 0);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void should_merge_permuted_duplicate_caller_ranges_before_promoting_the_caller() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget mismatchTarget = target("Mismatch", "work");
        SemanticMethod root = incomingMethod(rootTarget, 20);
        SemanticMethod direct = incomingMethod(directTarget, 10);
        SemanticRange exactSite = semanticRange(1);
        SemanticRange mismatchSite = semanticRange(2);
        FakeSemanticService firstSemantic = new FakeSemanticService()
                .incomingResult(root, new SemanticIncomingCallResult(
                        List.of(incoming(direct, mismatchSite), incoming(direct, exactSite)), List.of()))
                .resolution(direct, exactSite, localCall(root, exactSite))
                .resolution(direct, mismatchSite, localCall(incomingMethod(mismatchTarget, 30), mismatchSite));
        FakeSemanticService secondSemantic = new FakeSemanticService()
                .incomingResult(root, new SemanticIncomingCallResult(
                        List.of(incoming(direct, exactSite), incoming(direct, mismatchSite)), List.of()))
                .resolution(direct, exactSite, localCall(root, exactSite))
                .resolution(direct, mismatchSite, localCall(incomingMethod(mismatchTarget, 30), mismatchSite));

        IncomingGraphFragment first = builder(firstSemantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, mismatchTarget), rootTarget, root, 1, 0);
        IncomingGraphFragment second = builder(secondSemantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, mismatchTarget), rootTarget, root, 1, 0);

        assertThat(first).isEqualTo(second);
        assertThat(first.edges()).singleElement();
        assertThat(first.warnings()).singleElement().satisfies(warning -> {
            assertThat(first.nodes()).filteredOn(node -> node.target().filter(directTarget::equals).isPresent())
                    .singleElement().extracting(node -> node.nodeId()).isEqualTo(warning.nodeId());
        });
    }

    @Test
    void should_treat_root_empty_as_complete_but_root_only_rejections_as_protocol_failure() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = incomingMethod(rootTarget, 0);

        IncomingGraphFragment empty = builder(new FakeSemanticService())
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 0);

        assertThat(empty.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(empty.nodes()).singleElement();
        assertThatThrownBy(() -> builder(new FakeSemanticService().incomingResult(root,
                        new SemanticIncomingCallResult(List.of(), List.of(SemanticIncomingCallIssue.callerRejected()))))
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 0))
                .isExactlyInstanceOf(SemanticProtocolException.class);
    }

    @Test
    void should_reject_root_when_every_adapter_caller_canonicalization_fails() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = incomingMethod(rootTarget, 0);
        SemanticMethod rejectedCaller = incomingMethod(target("RejectedCaller", "run"), 2);

        assertThatThrownBy(() -> builder(new FakeSemanticService()
                .incoming(root, incoming(rejectedCaller, semanticRange(2))))
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 0))
                .isExactlyInstanceOf(SemanticProtocolException.class);
    }

    @Test
    void should_retain_valid_root_siblings_and_record_one_sanitized_rejection_warning() {
        MethodTarget calleeTarget = target("Callee", "work");
        MethodTarget callerTarget = target("Caller", "run");
        SemanticMethod callee = incomingMethod(calleeTarget, 10);
        SemanticMethod caller = incomingMethod(callerTarget, 0);
        SemanticRange callSite = semanticRange(2);
        FakeSemanticService semantic = new FakeSemanticService()
                .incomingResult(callee, new SemanticIncomingCallResult(
                        List.of(incoming(caller, callSite)),
                        List.of(SemanticIncomingCallIssue.callerRejected(), SemanticIncomingCallIssue.callerRejected())))
                .resolution(caller, callSite, localCall(callee, callSite));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(calleeTarget, callerTarget), calleeTarget, callee, 1, 0);

        assertThat(fragment.edges()).singleElement();
        assertThat(fragment.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("INCOMING_CALLER_REJECTED");
            assertThat(warning.message()).contains("count: 2");
        });
    }

    @Test
    void should_propagate_root_failures_and_retain_descendant_query_failures() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        SemanticMethod root = incomingMethod(rootTarget, 10);
        SemanticMethod direct = incomingMethod(directTarget, 0);
        SemanticRange site = semanticRange(2);

        assertThatThrownBy(() -> builder(new FakeSemanticService().failIncoming(root))
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 0))
                .isInstanceOf(IllegalStateException.class);

        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(root, incoming(direct, site))
                .resolution(direct, site, localCall(root, site))
                .failIncoming(direct);
        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget), rootTarget, root, 2, 1);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.errors()).singleElement().extracting(error -> error.code())
                .isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
    }

    @Test
    void should_propagate_root_forward_validation_failure_and_record_descendant_original_failure() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget rootCallerTarget = target("RootCaller", "work");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget descendantTarget = target("Descendant", "work");
        MethodTarget interfaceTarget = target("Port", "work");
        SemanticMethod root = incomingMethod(rootTarget, 20);
        SemanticMethod rootCaller = incomingMethod(rootCallerTarget, 0);
        SemanticMethod direct = incomingMethod(directTarget, 10);
        SemanticMethod descendant = incomingMethod(descendantTarget, 2);
        SemanticMethod declaration = incomingMethod(interfaceTarget, 30);
        SemanticRange rootSite = semanticRange(1);
        SemanticRange descendantSite = semanticRange(2);
        SemanticProtocolException rootFailure = new SemanticProtocolException();
        FakeSemanticService rootSemantic = new FakeSemanticService()
                .incoming(root, incoming(rootCaller, rootSite))
                .resolution(rootCaller, rootSite, localCall(declaration, rootSite))
                .failImplementations(declaration, rootFailure);

        assertThatThrownBy(() -> builder(rootSemantic)
                .build(SNAPSHOT, syntax(type(rootTarget), type(rootCallerTarget), interfaceType(interfaceTarget)),
                        rootTarget, root, 1, 0))
                .isSameAs(rootFailure);

        FakeSemanticService descendantSemantic = new FakeSemanticService()
                .incoming(root, incoming(direct, rootSite))
                .incoming(direct, incoming(descendant, descendantSite))
                .resolution(direct, rootSite, localCall(root, rootSite))
                .resolution(descendant, descendantSite, localCall(declaration, descendantSite))
                .failImplementations(declaration, new SemanticProtocolException());

        IncomingGraphFragment fragment = builder(descendantSemantic)
                .build(SNAPSHOT,
                        syntax(type(rootTarget), type(directTarget), type(descendantTarget), interfaceType(interfaceTarget)),
                        rootTarget, root, 2, 1);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.errors()).singleElement().extracting(error -> error.code())
                .isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
    }

    @Test
    void should_apply_depth_two_budget_deterministically_and_allow_cutoff_target_to_be_rerooted() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget alphaTarget = target("Alpha", "save");
        MethodTarget zetaTarget = target("Zeta", "save");
        SemanticMethod root = incomingMethod(rootTarget, 20);
        SemanticMethod direct = incomingMethod(directTarget, 10);
        SemanticMethod alpha = incomingMethod(alphaTarget, 0);
        SemanticMethod zeta = incomingMethod(zetaTarget, 2);
        SemanticRange directSite = semanticRange(12);
        SemanticRange alphaSite = semanticRange(2);
        SemanticRange zetaSite = semanticRange(4);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(root, incoming(direct, directSite))
                .incoming(direct, incoming(zeta, zetaSite), incoming(alpha, alphaSite))
                .resolution(direct, directSite, localCall(root, directSite))
                .resolution(alpha, alphaSite, localCall(direct, alphaSite))
                .resolution(zeta, zetaSite, localCall(direct, zetaSite));
        IncomingSemanticCallGraphBuilder builder = builder(semantic);

        IncomingGraphFragment fragment = builder
                .build(SNAPSHOT, syntax(rootTarget, directTarget, alphaTarget, zetaTarget), rootTarget, root, 2, 1);

        assertThat(fragment.traversal().expandedNodeCount()).isEqualTo(1);
        assertThat(fragment.traversal().limitReason()).isEqualTo(GraphLimitReason.NODE_BUDGET);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(alphaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(zetaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);

        IncomingGraphFragment rerooted = builder
                .build(SNAPSHOT, syntax(rootTarget, directTarget, alphaTarget, zetaTarget), zetaTarget, zeta, 1, 0);
        assertThat(rerooted.nodes()).filteredOn(node -> node.target().filter(zetaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
    }

    @Test
    void should_not_charge_cycles_or_reused_nodes_against_the_depth_two_budget() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        SemanticMethod root = incomingMethod(rootTarget, 10);
        SemanticMethod direct = incomingMethod(directTarget, 0);
        SemanticRange directSite = semanticRange(2);
        SemanticRange cycleSite = semanticRange(4);
        FakeSemanticService semantic = new FakeSemanticService()
                .incoming(root, incoming(direct, directSite))
                .incoming(direct, incoming(root, cycleSite))
                .resolution(direct, directSite, localCall(root, directSite))
                .resolution(root, cycleSite, localCall(direct, cycleSite));

        IncomingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget), rootTarget, root, 2, 0);

        assertThat(fragment.edges()).hasSize(2);
        assertThat(fragment.traversal().expandedNodeCount()).isZero();
        assertThat(fragment.traversal().limitReason()).isEqualTo(GraphLimitReason.NONE);
        assertThat(fragment.warnings()).hasSize(0);
    }

    private static IncomingSemanticCallGraphBuilder builder(FakeSemanticService semanticService) {
        return new IncomingSemanticCallGraphBuilder(
                semanticService, new DirectCallRelationshipResolver(semanticService, new SpringImplementationSelector()));
    }

    private static RepositorySyntax syntax(MethodTarget... targets) {
        return new RepositorySyntax(List.of(), Arrays.stream(targets).map(IncomingSemanticCallGraphBuilderTest::type).toList());
    }

    private static RepositorySyntax syntax(ClassMetadata... types) {
        return new RepositorySyntax(List.of(), List.of(types));
    }

    private static ClassMetadata type(MethodTarget target) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(30, 0));
        List<SyntaxInvocation> invocations = List.of(
                invocation(1), invocation(2), invocation(3), invocation(4), invocation(5), invocation(7), invocation(12));
        MethodSignature method = new MethodSignature(
                target.methodName(), target.parameterTypes(), List.of(), null, null, 1, 6,
                range, new SourceSlice(range, "void " + target.methodName() + "() {}"), List.<TypeReference>of(),
                Optional.empty(), invocations, List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), true, true);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), ClassMetadata.TypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(method), false, false, List.of(), range,
                new SourceSlice(range, "class " + target.className() + " {}"), false, List.of());
    }

    private static ClassMetadata interfaceType(MethodTarget target) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(30, 0));
        MethodSignature method = new MethodSignature(
                target.methodName(), target.parameterTypes(), List.of(), null, null, 1, 6,
                range, new SourceSlice(range, "void " + target.methodName() + "() {}"), List.<TypeReference>of(),
                Optional.empty(), List.of(), List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), false, true);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), ClassMetadata.TypeKind.INTERFACE, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(method), false, false, List.of(), range,
                new SourceSlice(range, "interface " + target.className() + " {}"), false, List.of());
    }

    private static SyntaxInvocation invocation(int line) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(line, 0), new SyntaxPosition(line, 4));
        return new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD, range, "work()",
                "worker", "", "", Optional.empty(), range.start());
    }

    private static SemanticRange semanticRange(int line) {
        return new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
    }

    private static SemanticIncomingCall incoming(SemanticMethod caller, SemanticRange... sites) {
        return new SemanticIncomingCall(caller, caller.methodName() + "()", List.of(sites));
    }

    private static SemanticCallResolution localCall(SemanticMethod target, SemanticRange site) {
        return SemanticCallResolution.resolved(new SemanticCall(
                Optional.of(target), target.methodName() + "()", List.of(site), false,
                SemanticResolutionOrigin.DEFINITION_FALLBACK));
    }

    private static SemanticCallResolution externalCall(SemanticRange site) {
        return SemanticCallResolution.resolved(new SemanticCall(
                Optional.empty(), "library.work()", List.of(site), true, SemanticResolutionOrigin.DEFINITION_FALLBACK,
                com.java.semantic.semantic.domain.SemanticCallStatus.IDENTITY_UNPROVEN));
    }

    private static final class FakeSemanticService implements JavaSemanticService {

        private final Map<SemanticMethod, SemanticIncomingCallResult> incoming = new HashMap<>();
        private final Map<SemanticCallSite, SemanticCallResolution> resolutions = new HashMap<>();
        private final Map<SemanticMethod, RuntimeException> implementationFailures = new HashMap<>();
        private final List<SemanticMethod> failingIncoming = new ArrayList<>();
        private final List<SemanticMethod> incomingQueries = new ArrayList<>();

        FakeSemanticService incoming(SemanticMethod callee, SemanticIncomingCall... calls) {
            return incomingResult(callee, new SemanticIncomingCallResult(List.of(calls), List.of()));
        }

        FakeSemanticService incomingResult(SemanticMethod callee, SemanticIncomingCallResult result) {
            incoming.put(callee, result);
            return this;
        }

        FakeSemanticService resolution(SemanticMethod caller, SemanticRange range, SemanticCallResolution resolution) {
            resolutions.put(new SemanticCallSite(range, range.start()), resolution);
            return this;
        }

        FakeSemanticService failIncoming(SemanticMethod callee) {
            failingIncoming.add(callee);
            return this;
        }

        FakeSemanticService failImplementations(SemanticMethod declaration, RuntimeException exception) {
            implementationFailures.put(declaration, exception);
            return this;
        }

        List<SemanticMethod> incomingQueries() {
            return List.copyOf(incomingQueries);
        }

        @Override
        public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SemanticCallResolution resolveCallResolutionAt(
                RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
            return resolutions.getOrDefault(callSite, SemanticCallResolution.unresolved());
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
            return List.of();
        }

        @Override
        public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
            incomingQueries.add(callee);
            if (failingIncoming.contains(callee)) {
                throw new IllegalStateException("planned incoming query failure");
            }
            return incoming.getOrDefault(callee, SemanticIncomingCallResult.empty());
        }

        @Override
        public List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            Optional.ofNullable(implementationFailures.get(method)).ifPresent(exception -> {
                throw exception;
            });
            return List.of();
        }
    }
}
