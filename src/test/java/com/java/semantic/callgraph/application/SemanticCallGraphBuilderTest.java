package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
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
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SqlSourceKind;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.outgoingMethod;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.sourceClassification;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.resolvedCall;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.target;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class SemanticCallGraphBuilderTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_return_success_for_a_root_without_calls() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = outgoingMethod(rootTarget, 0);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(new FakeSemanticService())
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 2, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.nodes()).singleElement().satisfies(node -> {
            assertThat(node.target()).contains(rootTarget);
            assertThat(node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
            assertThat(node.traversalState()).isEqualTo(NodeTraversalState.EXPANDED);
        });
        assertThat(fragment.traversal().rootDirectCallsComplete()).isTrue();
        assertThat(fragment.traversal().expandedNodeCount()).isZero();
    }

    @Test
    void should_keep_every_depth_one_local_callee_as_full_source_boundary() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(child, 2));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 1, 0);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(childTarget::equals).isPresent()).singleElement()
                .satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
                    assertThat(node.traversalState()).isEqualTo(NodeTraversalState.DEPTH_BOUNDARY);
                });
        assertThat(fragment.traversal().expandedNodeCount()).isZero();
        assertThat(fragment.edges()).hasSize(1);
    }

    @Test
    void should_apply_budget_only_to_depth_two_source_hydration() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget grandchildTarget = target("Grandchild", "save");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod direct = outgoingMethod(directTarget, 10);
        SemanticMethod grandchild = outgoingMethod(grandchildTarget, 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(direct, 2))
                .outgoing(direct, resolvedCall(grandchild, 12));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, grandchildTarget), rootTarget, root, 2, 0);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(directTarget::equals).isPresent()).singleElement()
                .extracting(node -> node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(grandchildTarget::equals).isPresent()).singleElement()
                .satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(node.traversalState()).isEqualTo(NodeTraversalState.BUDGET_CUTOFF);
                });
        assertThat(fragment.traversal().limitReason()).isEqualTo(GraphLimitReason.NODE_BUDGET);
        assertThat(fragment.warnings()).extracting(warning -> warning.code()).contains("NODE_BUDGET_REACHED");
    }

    @Test
    void should_return_a_root_call_site_diagnostic_without_guessing_an_unproven_local_edge() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget unprovenTarget = target("Unproven", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod unproven = outgoingMethod(unprovenTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(unproven, 2));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).singleElement()
                .extracting(warning -> warning.code()).isEqualTo("DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.traversal().rootDirectCallsComplete()).isTrue();
    }

    @Test
    void should_use_exact_declaration_range_when_jdt_parameter_names_are_not_fully_qualified() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Child"),
                        "Child.java"),
                "work",
                List.of("com.example.PlaceOrderRequest"));
        SemanticMethod root = outgoingMethod(rootTarget, 10);
        SemanticRange declarationRange = new SemanticRange(
                new SemanticPosition(0, 0), new SemanticPosition(5, 0));
        SemanticMethod childFromJdt = new SemanticMethod(
                "com.example",
                "Child",
                "work",
                List.of("PlaceOrderRequest"),
                "void",
                new SemanticLocation("file:///fixture/Child.java", declarationRange, declarationRange));
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(childFromJdt, 12));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 1, 40);

        assertThat(fragment.edges()).singleElement();
        assertThat(fragment.nodes())
                .filteredOn(node -> node.target().filter(childTarget::equals).isPresent())
                .hasSize(1);
        assertThat(fragment.warnings()).isEmpty();
    }

    @Test
    void should_retain_a_usable_fragment_when_depth_one_enumeration_fails() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(child, 2))
                .failOutgoing(child);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 2, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.edges()).hasSize(1);
        assertThat(fragment.errors()).singleElement()
                .extracting(error -> error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
    }

    @Test
    void should_warn_without_an_edge_for_a_targetless_root_hierarchy_call() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticCall targetless = new SemanticCall(Optional.empty(), "unknown()", List.of(new SemanticRange(
                new SemanticPosition(2, 0), new SemanticPosition(2, 4))), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, targetless))
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
    }

    @Test
    void should_warn_without_an_edge_for_a_targetless_depth_one_hierarchy_call() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        SemanticCall targetless = new SemanticCall(Optional.empty(), "unknown()", List.of(new SemanticRange(
                new SemanticPosition(12, 0), new SemanticPosition(12, 4))), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(child, 2))
                .outgoing(child, targetless);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 2, 40);

        assertThat(fragment.edges()).hasSize(1);
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
    }

    @Test
    void should_keep_distinct_external_calls_when_their_targets_are_not_proven() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticCall first = externalCall("library.alpha()", 2);
        SemanticCall second = externalCall("library.beta()", 3);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, first, second))
                .build(SNAPSHOT, syntax(rootTarget), rootTarget, root, 1, 40);

        assertThat(fragment.edges()).hasSize(2);
        assertThat(fragment.nodes()).filteredOn(node -> NodeContentState.EXTERNAL.equals(node.contentState()))
                .extracting(node -> node.externalSymbol())
                .containsExactlyInAnyOrder("library.alpha()", "library.beta()");
    }

    @Test
    void should_propagate_root_interface_resolution_failures() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget interfaceTarget = target("Port", "handle");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 10);
        IllegalStateException expected = new IllegalStateException("planned implementation query failure");
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(declaration, 2))
                .failImplementations(declaration, expected);

        assertThatThrownBy(() -> builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, interfaceType(interfaceTarget)), rootTarget, root, 1, 40))
                .isSameAs(expected);
    }

    @Test
    void should_rethrow_root_point_resolution_failures() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SyntaxInvocation invocation = invocation("worker.failed()", 2);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(invocation.range()), new SemanticPosition(2, 0));
        IllegalStateException expected = new IllegalStateException("planned point resolution failure");
        FakeSemanticService semantic = new FakeSemanticService().failResolutionAt(callSite, expected);

        assertThatThrownBy(() -> builder(semantic)
                .build(SNAPSHOT,
                        syntax(List.of(type(rootTarget, List.of(invocation)))),
                        rootTarget,
                        root,
                        1,
                        40))
                .isSameAs(expected);
    }

    @Test
    void should_preserve_successful_child_relationships_when_another_interface_resolution_fails() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        MethodTarget interfaceTarget = target("Port", "handle");
        MethodTarget successfulTarget = target("Successful", "save");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 20);
        SemanticMethod successful = outgoingMethod(successfulTarget, 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(child, 2))
                .outgoing(child, resolvedCall(declaration, 12), resolvedCall(successful, 14))
                .failImplementations(declaration);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(
                                type(rootTarget),
                                type(childTarget),
                                interfaceType(interfaceTarget),
                                type(successfulTarget))),
                        rootTarget, root, 2, 40);

        assertThat(fragment.edges()).extracting(edge -> edge.callSite().startLine())
                .containsExactly(2, 14);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(successfulTarget::equals).isPresent())
                .singleElement();
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.errors()).extracting(error -> error.code())
                .containsExactly("CHILD_SEMANTIC_QUERY_FAILED");
    }

    @Test
    void should_retain_later_child_fallback_edges_when_an_earlier_point_resolution_fails() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        MethodTarget grandchildTarget = target("Grandchild", "save");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        SemanticMethod grandchild = outgoingMethod(grandchildTarget, 20);
        SyntaxInvocation failedInvocation = invocation("worker.failed()", 12);
        SyntaxInvocation successfulInvocation = invocation("worker.save()", 14);
        SemanticCallSite failedCallSite = new SemanticCallSite(
                semanticRange(failedInvocation.range()), new SemanticPosition(12, 0));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(child, 2))
                .resolution(child, SemanticCallResolution.resolved(resolvedCall(grandchild, 14)))
                .failResolutionAt(failedCallSite);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT,
                        syntax(List.of(
                                type(rootTarget),
                                type(childTarget, List.of(failedInvocation, successfulInvocation)),
                                type(grandchildTarget))),
                        rootTarget,
                        root,
                        2,
                        40);

        assertThat(fragment.edges()).extracting(edge -> edge.callSite().startLine())
                .containsExactly(2, 14);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(grandchildTarget::equals).isPresent())
                .singleElement();
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.errors()).extracting(error -> error.code())
                .containsExactly("CHILD_SEMANTIC_QUERY_FAILED");
    }

    @Test
    void should_assign_response_local_node_ids_for_each_root() {
        MethodTarget firstTarget = target("First", "run");
        MethodTarget secondTarget = target("Second", "run");
        SemanticCallGraphBuilder builder = builder(new FakeSemanticService());

        com.java.semantic.callgraph.domain.OutgoingGraphFragment first = builder
                .build(SNAPSHOT, syntax(firstTarget), firstTarget, outgoingMethod(firstTarget, 0), 1, 40);
        com.java.semantic.callgraph.domain.OutgoingGraphFragment second = builder
                .build(SNAPSHOT, syntax(secondTarget), secondTarget, outgoingMethod(secondTarget, 0), 1, 40);

        assertThat(first.rootNodeId().value()).isEqualTo("node-0000");
        assertThat(second.rootNodeId().value()).isEqualTo("node-0000");
        assertThat(second.nodes()).singleElement().extracting(node -> node.target().orElseThrow())
                .isEqualTo(secondTarget);
    }

    @Test
    void should_deduplicate_repeated_call_sites_and_order_a_cycle_deterministically() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);
        SemanticCall repeatedCall = resolvedCall(child, 2);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, repeatedCall, repeatedCall)
                .outgoing(child, resolvedCall(root, 12));
        SemanticCallGraphBuilder builder = builder(semantic);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment first = builder
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 2, 40);
        com.java.semantic.callgraph.domain.OutgoingGraphFragment second = builder
                .build(SNAPSHOT, syntax(rootTarget, childTarget), rootTarget, root, 2, 40);

        assertThat(first.nodes()).hasSize(2).containsExactlyElementsOf(second.nodes());
        assertThat(first.edges()).hasSize(2).containsExactlyElementsOf(second.edges());
        assertThat(first.edges()).extracting(
                        edge -> edge.callerNodeId().value(),
                        edge -> edge.calleeNodeId().value(),
                        edge -> edge.callSite().startLine())
                .containsExactly(
                        tuple("node-0000", "node-0001", 2),
                        tuple("node-0001", "node-0000", 12));
    }

    @Test
    void should_hydrate_deterministically_up_to_a_positive_depth_two_budget() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget directTarget = target("Direct", "work");
        MethodTarget zetaTarget = target("Zeta", "save");
        MethodTarget alphaTarget = target("Alpha", "save");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod direct = outgoingMethod(directTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(direct, 2))
                .outgoing(direct, resolvedCall(outgoingMethod(zetaTarget, 20), 14), resolvedCall(outgoingMethod(alphaTarget, 30), 12));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), type(directTarget), asyncType(zetaTarget), type(alphaTarget))),
                        rootTarget, root, 2, 1);

        assertThat(fragment.traversal().expandedNodeCount()).isEqualTo(1);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(alphaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(zetaTarget::equals).isPresent())
                .singleElement().satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(node.dispatchKind()).isEqualTo(DispatchKind.ASYNC);
                });
    }

    @Test
    void should_keep_full_source_state_and_ranges_for_an_annotated_depth_one_target() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, resolvedCall(child, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), annotatedType(childTarget))), rootTarget, root, 1, 40);

        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(childTarget::equals).isPresent())
                .singleElement().satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
                    assertThat(node.declarationRange()).contains(new com.java.semantic.callgraph.domain.CallSiteRange(
                            childTarget.sourceFile(), 0, 0, 5, 0));
                    assertThat(node.dispatchKind()).isEqualTo(DispatchKind.SYNCHRONOUS);
                });
    }

    @Test
    void should_mark_an_async_annotated_depth_one_target_as_asynchronous_dispatch() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod child = outgoingMethod(childTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, resolvedCall(child, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), asyncType(childTarget))), rootTarget, root, 1, 40);

        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(childTarget::equals).isPresent())
                .singleElement().extracting(node -> node.dispatchKind()).isEqualTo(DispatchKind.ASYNC);
    }

    @Test
    void should_use_source_qualified_interface_metadata_when_fqns_are_duplicated() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget interfaceTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Port"),
                        "PortInterface.java"),
                "handle",
                List.of());
        MethodTarget duplicateClassTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Port"),
                        "PortClass.java"),
                "handle",
                List.of());
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod interfaceMethod = outgoingMethod(interfaceTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, resolvedCall(interfaceMethod, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), type(duplicateClassTarget), interfaceType(interfaceTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
    }

    @Test
    void should_classify_interface_by_exact_method_target_when_metadata_uses_canonical_source_locations() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget declarationTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Port"),
                        "src/main/java/com/example/Port.java"),
                "handle",
                List.of());
        MethodTarget cardTarget = target("CardPort", "handle");
        MethodTarget cashTarget = target("CashPort", "handle");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod declaration = outgoingMethod(declarationTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(declaration, 2))
                .implementations(declaration, outgoingMethod(cardTarget, 20), outgoingMethod(cashTarget, 30));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(
                                type(rootTarget),
                                type(declarationTarget, SourceTypeKind.INTERFACE, false),
                                type(cardTarget),
                                type(cashTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("DESCENDANT_CALL_AMBIGUOUS");
            assertThat(warning.candidates()).containsExactly(cardTarget, cashTarget);
        });
    }

    @Test
    void should_report_complete_sorted_candidates_for_an_uncovered_ambiguous_syntax_call() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget zetaTarget = target("Zeta", "work");
        MethodTarget alphaTarget = target("Alpha", "work");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SyntaxInvocation invocation = invocation("worker.work()", 2);
        FakeSemanticService semantic = new FakeSemanticService().resolution(root,
                SemanticCallResolution.ambiguous(List.of(outgoingMethod(zetaTarget, 20), outgoingMethod(alphaTarget, 10))));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget, List.of(invocation)), type(zetaTarget), type(alphaTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("DESCENDANT_CALL_AMBIGUOUS");
            assertThat(warning.candidates()).containsExactly(alphaTarget, zetaTarget);
        });
    }

    @Test
    void should_select_an_executable_interface_default_without_implementations() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget defaultTarget = target("Port", "handle");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod defaultMethod = outgoingMethod(defaultTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, resolvedCall(defaultMethod, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), executableInterfaceType(defaultTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).singleElement().extracting(edge -> edge.resolutionStrategy())
                .isEqualTo(com.java.semantic.callgraph.domain.ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
    }

    @Test
    void should_report_an_executable_interface_default_and_override_as_ambiguous() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget defaultTarget = target("Port", "handle");
        MethodTarget overrideTarget = target("PortOverride", "handle");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod defaultMethod = outgoingMethod(defaultTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(defaultMethod, 2))
                .implementations(defaultMethod, outgoingMethod(overrideTarget, 20));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), executableInterfaceType(defaultTarget), type(overrideTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).singleElement().extracting(warning -> warning.code())
                .isEqualTo("DESCENDANT_CALL_AMBIGUOUS");
    }

    @Test
    void should_select_one_implementation_for_a_non_executable_interface() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget declarationTarget = target("Port", "handle");
        MethodTarget implementationTarget = target("PortImpl", "handle");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod declaration = outgoingMethod(declarationTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, resolvedCall(declaration, 2))
                .implementations(declaration, outgoingMethod(implementationTarget, 20));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), interfaceType(declarationTarget), type(implementationTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).singleElement().extracting(edge -> edge.resolutionStrategy())
                .isEqualTo(com.java.semantic.callgraph.domain.ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
        assertThat(fragment.edges()).singleElement().satisfies(edge ->
                assertThat(edge.declarationTarget()).contains(declarationTarget));
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(implementationTarget::equals).isPresent())
                .hasSize(1);
    }

    @Test
    void should_relabel_a_lombok_generated_getter_as_a_suppressed_opaque_edge() {
        MethodTarget rootTarget = target("Caller", "run");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SyntaxInvocation getterInvocation = receiverInvocation("order.getTotal()", 2, "com.example.Order");
        SourceTypeMetadata callerType = type(rootTarget, List.of(getterInvocation));
        SourceTypeMetadata orderType = lombokDataType("com.example.Order", "total");

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(new FakeSemanticService())
                .build(SNAPSHOT, new RepositorySyntax(List.of(), List.of(callerType, orderType)),
                        rootTarget, root, 1, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.warnings()).isEmpty();
        assertThat(fragment.edges()).singleElement()
                .extracting(edge -> edge.resolutionStrategy())
                .isEqualTo(com.java.semantic.callgraph.domain.ResolutionStrategy.LOMBOK_GENERATED);
        assertThat(fragment.nodes()).filteredOn(node -> NodeContentState.EXTERNAL.equals(node.contentState()))
                .singleElement().satisfies(node -> {
                    assertThat(node.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
                    assertThat(node.externalSymbol()).isEqualTo("com.example.Order#getTotal()");
                });
    }

    @Test
    void should_relabel_a_mybatis_mapper_with_sql_as_a_suppressed_opaque_edge() {
        MethodTarget rootTarget = target("Caller", "run");
        MethodTarget mapperTarget = target("OrderMapper", "insert");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod mapperDeclaration = outgoingMethod(mapperTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(mapperDeclaration, 2));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget),
                                mapperInterfaceType(mapperTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.warnings()).isEmpty();
        assertThat(fragment.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy())
                    .isEqualTo(com.java.semantic.callgraph.domain.ResolutionStrategy.MYBATIS_MAPPER);
            assertThat(edge.evidence()).containsExactly("mapper SQL evidence: ANNOTATION");
        });
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(mapperTarget::equals).isPresent())
                .singleElement().satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(node.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
                });
    }

    @Test
    void should_preserve_mapper_statement_identity_on_outgoing_opaque_edge() {
        MethodTarget rootTarget = target("Caller", "run");
        MethodTarget mapperTarget = target("OrderMapper", "insert");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod mapperDeclaration = outgoingMethod(mapperTarget, 10);
        MapperStatementIdentity identity = mapperIdentity(mapperTarget, "mapper/OrderMapper.xml", Optional.empty(), 0);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(mapperDeclaration, 2));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT,
                        syntaxWithMapperEvidence(
                                List.of(type(rootTarget), mapperInterfaceType(mapperTarget)),
                                List.of(statementEvidence(identity, mapperTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).singleElement()
                .satisfies(edge -> assertThat(edge.evidenceSourceIdentities()).containsExactly(identity));
    }

    @Test
    void should_relabel_an_annotated_dao_without_evidence_and_keep_the_warning() {
        MethodTarget rootTarget = target("Caller", "run");
        MethodTarget mapperTarget = target("PaymentMapper", "insertPayment");
        SemanticMethod root = outgoingMethod(rootTarget, 0);
        SemanticMethod mapperDeclaration = outgoingMethod(mapperTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, resolvedCall(mapperDeclaration, 2));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), annotatedMapperInterfaceType(mapperTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
        assertThat(fragment.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(
                    com.java.semantic.callgraph.domain.ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE);
        });
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(mapperTarget::equals).isPresent())
                .singleElement().satisfies(node -> {
                    assertThat(node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(node.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
                });
    }

    private static SyntaxInvocation receiverInvocation(String expression, int line, String receiverDeclaration) {
        SyntaxRange invocationRange = range(line, 0, line, expression.length());
        return new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD, invocationRange, expression,
                "receiver", receiverDeclaration, "", Optional.empty(), invocationRange.start());
    }

    private static SourceTypeMetadata lombokDataType(String fullyQualifiedName, String fieldName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        String simpleName = fullyQualifiedName.substring(lastDot + 1);
        String packageName = fullyQualifiedName.substring(0, lastDot);
        SyntaxRange range = range(0, 0, 10, 0);
        SourceFieldMetadata field = new SourceFieldMetadata(
                fieldName, "BigDecimal", "", new NamedTypeReference(
                        "BigDecimal", "BigDecimal", Optional.empty(), false), List.of());
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                simpleName, packageName, fullyQualifiedName, simpleName + ".java",
                SourceTypeKind.CLASS, false, List.of(), List.of(), List.of("Data"), List.of(),
                List.of(field), List.of(), false, false, List.of(), range,
                new SourceRange(simpleName + ".java", range), false, List.of());
    }

    private static SourceTypeMetadata mapperInterfaceType(MethodTarget target) {
        return mapperInterfaceType(target, List.of(), SqlSourceKind.ANNOTATION);
    }

    private static SourceTypeMetadata annotatedMapperInterfaceType(MethodTarget target) {
        return mapperInterfaceType(target, List.of("Mapper"), null);
    }

    private static SourceTypeMetadata mapperInterfaceType(
            MethodTarget target, List<String> annotations, SqlSourceKind sqlSource) {
        SyntaxRange typeRange = range(0, 0, 30, 0);
        SyntaxRange methodRange = range(0, 0, 5, 0);
        SourceRange declarationLocation = new SourceRange(target.sourceFile(), methodRange);
        SourceMethodMetadata declaration = new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), sqlSource,
                sqlSource == SqlSourceKind.ANNOTATION ? Optional.of(declarationLocation) : Optional.empty(),
                declarationLocation,
                List.<TypeReference>of(), Optional.empty(), List.of(), List.of(), List.of(), methodRange.start(),
                MethodTargetResolution.resolved(target), false, true, false);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.INTERFACE, false,
                List.of(), List.of(), annotations, List.of(), List.of(),
                List.of(declaration), false, false, List.of(), typeRange,
                new SourceRange(target.sourceFile(), typeRange), false, List.of());
    }

    private SemanticCallGraphBuilder builder(JavaSemanticService semanticService) {
        return new SemanticCallGraphBuilder(semanticService, new SpringImplementationSelector());
    }

    private static RepositorySyntax syntax(MethodTarget... targets) {
        return syntax(Arrays.stream(targets).map(SemanticCallGraphBuilderTest::type).toList());
    }

    private static RepositorySyntax syntax(MethodTarget target, SourceTypeMetadata additionalType) {
        return syntax(List.of(type(target), additionalType));
    }

    private static RepositorySyntax syntax(List<SourceTypeMetadata> types) {
        return new RepositorySyntax(List.of(), types);
    }

    private static RepositorySyntax syntaxWithMapperEvidence(
            List<SourceTypeMetadata> types, List<MapperStatementEvidence> statements) {
        return new RepositorySyntax(
                List.of(), types, List.of(), Optional.of(new MapperEvidenceIndex(statements, List.of())));
    }

    private static MapperStatementIdentity mapperIdentity(
            MethodTarget mapperTarget, String resourcePath, Optional<String> databaseId, int documentOrdinal) {
        return new MapperStatementIdentity(
                new MapperStatementKey(mapperTarget.fullyQualifiedClassName(), mapperTarget.methodName()),
                resourcePath,
                databaseId,
                documentOrdinal,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    }

    private static MapperStatementEvidence statementEvidence(MapperStatementIdentity identity, MethodTarget target) {
        return new MapperStatementEvidence(
                identity,
                "insert",
                new SourceRange(identity.resourcePath(), range(0, 0, 1, 0)),
                List.of(),
                Optional.of(target));
    }

    private static SourceTypeMetadata type(MethodTarget target) {
        return type(target, SourceTypeKind.CLASS, true);
    }

    private static SourceTypeMetadata interfaceType(MethodTarget target) {
        return type(target, SourceTypeKind.INTERFACE, false);
    }

    private static SourceTypeMetadata executableInterfaceType(MethodTarget target) {
        return type(target, SourceTypeKind.INTERFACE, true);
    }

    private static SourceTypeMetadata type(MethodTarget target, List<SyntaxInvocation> invocations) {
        SyntaxRange range = range(0, 0, 30, 0);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, true, invocations)), false, false, List.of(), range,
                new SourceRange(target.sourceFile(), range), false, List.of());
    }

    private static SourceTypeMetadata annotatedType(MethodTarget target) {
        SyntaxRange range = range(0, 0, 30, 0);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, true, List.of())),
                false, false, List.of(), range, new SourceRange(target.sourceFile(), range),
                false, List.of());
    }

    private static SourceTypeMetadata asyncType(MethodTarget target) {
        SyntaxRange range = range(0, 0, 30, 0);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(asyncMethod(target)), false, false, List.of(), range,
                new SourceRange(target.sourceFile(), range), false, List.of());
    }

    private static SourceMethodMetadata asyncMethod(MethodTarget target) {
        SyntaxRange range = range(0, 0, 5, 0);
        return new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), null, Optional.empty(),
                new SourceRange(target.sourceFile(), range),
                List.<TypeReference>of(), Optional.empty(), List.of(),
                List.of(new AnnotationEvidence("Async", Optional.empty())), List.of(), range.start(),
                MethodTargetResolution.resolved(target), true, false, true);
    }

    private static SourceTypeMetadata type(MethodTarget target, SourceTypeKind kind, boolean executableDeclaration) {
        SyntaxRange range = range(0, 0, 30, 0);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), kind, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, executableDeclaration)), false, false, List.of(), range,
                new SourceRange(target.sourceFile(), range), false, List.of());
    }

    private static SourceMethodMetadata method(MethodTarget target) {
        return method(target, true);
    }

    private static SourceMethodMetadata method(MethodTarget target, boolean executableDeclaration) {
        return method(target, executableDeclaration, List.of());
    }

    private static SourceMethodMetadata method(
            MethodTarget target,
            boolean executableDeclaration,
            List<SyntaxInvocation> invocations) {
        SyntaxRange range = range(0, 0, 5, 0);
        return new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), null, Optional.empty(),
                new SourceRange(target.sourceFile(), range),
                List.<TypeReference>of(), Optional.empty(), invocations, List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), executableDeclaration, !executableDeclaration, true);
    }

    private static SemanticCall externalCall(String rawSignature, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
        return new SemanticCall(Optional.empty(), rawSignature, List.of(range), true,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);
    }

    private static SyntaxInvocation invocation(String expression, int line) {
        SyntaxRange range = range(line, 0, line, 4);
        return new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD, range, expression,
                "worker", "", "", Optional.empty(), range.start());
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
    }

    private static SemanticRange semanticRange(SyntaxRange range) {
        return new SemanticRange(
                new SemanticPosition(range.start().line(), range.start().character()),
                new SemanticPosition(range.end().line(), range.end().character()));
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

        private final Map<SemanticMethod, List<SemanticCall>> outgoing = new HashMap<>();
        private final List<SemanticMethod> failingOutgoing = new java.util.ArrayList<>();
        private final Map<SemanticMethod, RuntimeException> failingImplementations = new HashMap<>();
        private final Map<SemanticMethod, List<SemanticMethod>> implementations = new HashMap<>();
        private final Map<SemanticMethod, SemanticCallResolution> resolutions = new HashMap<>();
        private final Map<SemanticCallSite, RuntimeException> failingResolutions = new HashMap<>();

        FakeSemanticService outgoing(SemanticMethod caller, SemanticCall... calls) {
            outgoing.put(caller, List.of(calls));
            return this;
        }

        FakeSemanticService failOutgoing(SemanticMethod caller) {
            failingOutgoing.add(caller);
            return this;
        }

        FakeSemanticService failImplementations(SemanticMethod declaration) {
            return failImplementations(declaration, new IllegalStateException("planned implementation query failure"));
        }

        FakeSemanticService failImplementations(SemanticMethod declaration, RuntimeException failure) {
            failingImplementations.put(declaration, failure);
            return this;
        }

        FakeSemanticService implementations(SemanticMethod declaration, SemanticMethod... methods) {
            implementations.put(declaration, List.of(methods));
            return this;
        }

        FakeSemanticService resolution(SemanticMethod caller, SemanticCallResolution resolution) {
            resolutions.put(caller, resolution);
            return this;
        }

        FakeSemanticService failResolutionAt(SemanticCallSite callSite) {
            return failResolutionAt(callSite, new IllegalStateException("planned point resolution failure"));
        }

        FakeSemanticService failResolutionAt(SemanticCallSite callSite, RuntimeException failure) {
            failingResolutions.put(callSite, failure);
            return this;
        }

        @Override
        public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SemanticCallResolution resolveCallResolutionAt(
                RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
            RuntimeException failure = failingResolutions.get(callSite);
            Optional.ofNullable(failure).ifPresent(exception -> {
                throw exception;
            });
            return resolutions.getOrDefault(caller, SemanticCallResolution.unresolved());
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
            if (failingOutgoing.contains(method)) {
                throw new IllegalStateException("planned child query failure");
            }
            return outgoing.getOrDefault(method, List.of());
        }

        @Override
        public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
            return SemanticIncomingCallResult.empty();
        }

        @Override
        public SemanticImplementationResult implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            if (failingImplementations.containsKey(method)) {
                throw failingImplementations.get(method);
            }
            return new SemanticImplementationResult(implementations.getOrDefault(method, List.of()), List.of());
        }
    }
}
