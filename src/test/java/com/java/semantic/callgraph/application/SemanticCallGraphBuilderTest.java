package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.identity.MethodTarget;
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
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class SemanticCallGraphBuilderTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_return_success_for_a_root_without_calls() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = method(rootTarget, 0);

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, call(child, 2));

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod direct = method(directTarget, 10);
        SemanticMethod grandchild = method(grandchildTarget, 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(direct, 2))
                .outgoing(direct, call(grandchild, 12));

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod unproven = method(unprovenTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, call(unproven, 2));

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
                "Child.java", "com.example", "Child", "work", List.of("com.example.PlaceOrderRequest"));
        SemanticMethod root = method(rootTarget, 10);
        SemanticRange declarationRange = new SemanticRange(
                new SemanticPosition(0, 0), new SemanticPosition(5, 0));
        SemanticMethod childFromJdt = new SemanticMethod(
                "com.example",
                "Child",
                "work",
                List.of("PlaceOrderRequest"),
                "void",
                new SemanticLocation("file:///fixture/Child.java", declarationRange, declarationRange));
        FakeSemanticService semantic = new FakeSemanticService().outgoing(root, call(childFromJdt, 12));

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, 2))
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
        SemanticMethod root = method(rootTarget, 0);
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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        SemanticCall targetless = new SemanticCall(Optional.empty(), "unknown()", List.of(new SemanticRange(
                new SemanticPosition(12, 0), new SemanticPosition(12, 4))), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, 2))
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
        SemanticMethod root = method(rootTarget, 0);
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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod declaration = method(interfaceTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(declaration, 2))
                .failImplementations(declaration);

        assertThatThrownBy(() -> builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, interfaceType(interfaceTarget)), rootTarget, root, 1, 40))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planned implementation query failure");
    }

    @Test
    void should_rethrow_root_point_resolution_failures() {
        MethodTarget rootTarget = target("Root", "run");
        SemanticMethod root = method(rootTarget, 0);
        SyntaxInvocation invocation = invocation("worker.failed()", 2);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(invocation.range()), new SemanticPosition(2, 0));
        FakeSemanticService semantic = new FakeSemanticService().failResolutionAt(callSite);

        assertThatThrownBy(() -> builder(semantic)
                .build(SNAPSHOT,
                        syntax(List.of(type(rootTarget, List.of(invocation)))),
                        rootTarget,
                        root,
                        1,
                        40))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planned point resolution failure");
    }

    @Test
    void should_preserve_successful_child_relationships_when_another_interface_resolution_fails() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        MethodTarget interfaceTarget = target("Port", "handle");
        MethodTarget successfulTarget = target("Successful", "save");
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        SemanticMethod declaration = method(interfaceTarget, 20);
        SemanticMethod successful = method(successfulTarget, 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, 2))
                .outgoing(child, call(declaration, 12), call(successful, 14))
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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        SemanticMethod grandchild = method(grandchildTarget, 20);
        SyntaxInvocation failedInvocation = invocation("worker.failed()", 12);
        SyntaxInvocation successfulInvocation = invocation("worker.save()", 14);
        SemanticCallSite failedCallSite = new SemanticCallSite(
                semanticRange(failedInvocation.range()), new SemanticPosition(12, 0));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, 2))
                .resolution(child, SemanticCallResolution.resolved(call(grandchild, 14)))
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
                .build(SNAPSHOT, syntax(firstTarget), firstTarget, method(firstTarget, 0), 1, 40);
        com.java.semantic.callgraph.domain.OutgoingGraphFragment second = builder
                .build(SNAPSHOT, syntax(secondTarget), secondTarget, method(secondTarget, 0), 1, 40);

        assertThat(first.rootNodeId().value()).isEqualTo("node-0000");
        assertThat(second.rootNodeId().value()).isEqualTo("node-0000");
        assertThat(second.nodes()).singleElement().extracting(node -> node.target().orElseThrow())
                .isEqualTo(secondTarget);
    }

    @Test
    void should_deduplicate_repeated_call_sites_and_order_a_cycle_deterministically() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);
        SemanticCall repeatedCall = call(child, 2);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, repeatedCall, repeatedCall)
                .outgoing(child, call(root, 12));
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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod direct = method(directTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(direct, 2))
                .outgoing(direct, call(method(zetaTarget, 20), 14), call(method(alphaTarget, 30), 12));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(rootTarget, directTarget, zetaTarget, alphaTarget), rootTarget, root, 2, 1);

        assertThat(fragment.traversal().expandedNodeCount()).isEqualTo(1);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(alphaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(zetaTarget::equals).isPresent())
                .singleElement().extracting(node -> node.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
    }

    @Test
    void should_keep_full_source_and_ranges_for_an_annotated_depth_one_target() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget childTarget = target("Child", "work");
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod child = method(childTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, call(child, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), annotatedType(childTarget))), rootTarget, root, 1, 40);

        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(childTarget::equals).isPresent())
                .singleElement().satisfies(node -> {
                    assertThat(node.methodBody()).contains("@Transactional\nvoid work() {}");
                    assertThat(node.declarationRange()).contains(new com.java.semantic.callgraph.domain.CallSiteRange(
                            childTarget.sourceFile(), 0, 0, 5, 0));
                });
    }

    @Test
    void should_use_source_qualified_interface_metadata_when_fqns_are_duplicated() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget interfaceTarget = new MethodTarget(
                "PortInterface.java", "com.example", "Port", "handle", List.of());
        MethodTarget duplicateClassTarget = new MethodTarget(
                "PortClass.java", "com.example", "Port", "handle", List.of());
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod interfaceMethod = method(interfaceTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, call(interfaceMethod, 2)))
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), type(duplicateClassTarget), interfaceType(interfaceTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).isEmpty();
        assertThat(fragment.warnings()).extracting(warning -> warning.code())
                .containsExactly("DESCENDANT_CALL_UNRESOLVED");
    }

    @Test
    void should_classify_interface_by_exact_method_target_when_metadata_uses_source_root_relative_paths() {
        MethodTarget rootTarget = target("Root", "run");
        MethodTarget declarationTarget = new MethodTarget(
                "src/main/java/com/example/Port.java", "com.example", "Port", "handle", List.of());
        MethodTarget cardTarget = target("CardPort", "handle");
        MethodTarget cashTarget = target("CashPort", "handle");
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod declaration = method(declarationTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(declaration, 2))
                .implementations(declaration, method(cardTarget, 20), method(cashTarget, 30));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(
                                type(rootTarget),
                                typeWithMetadataPath(
                                        declarationTarget,
                                        "com/example/Port.java",
                                        ClassMetadata.TypeKind.INTERFACE,
                                        false),
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
        SemanticMethod root = method(rootTarget, 0);
        SyntaxInvocation invocation = invocation("worker.work()", 2);
        FakeSemanticService semantic = new FakeSemanticService().resolution(root,
                SemanticCallResolution.ambiguous(List.of(method(zetaTarget, 20), method(alphaTarget, 10))));

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod defaultMethod = method(defaultTarget, 10);

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(
                new FakeSemanticService().outgoing(root, call(defaultMethod, 2)))
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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod defaultMethod = method(defaultTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(defaultMethod, 2))
                .implementations(defaultMethod, method(overrideTarget, 20));

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
        SemanticMethod root = method(rootTarget, 0);
        SemanticMethod declaration = method(declarationTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(declaration, 2))
                .implementations(declaration, method(implementationTarget, 20));

        com.java.semantic.callgraph.domain.OutgoingGraphFragment fragment = builder(semantic)
                .build(SNAPSHOT, syntax(List.of(type(rootTarget), interfaceType(declarationTarget), type(implementationTarget))),
                        rootTarget, root, 1, 40);

        assertThat(fragment.edges()).singleElement().extracting(edge -> edge.resolutionStrategy())
                .isEqualTo(com.java.semantic.callgraph.domain.ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
        assertThat(fragment.nodes()).filteredOn(node -> node.target().filter(implementationTarget::equals).isPresent())
                .hasSize(1);
    }

    private SemanticCallGraphBuilder builder(JavaSemanticService semanticService) {
        return new SemanticCallGraphBuilder(semanticService, new SpringImplementationSelector());
    }

    private static RepositorySyntax syntax(MethodTarget... targets) {
        return syntax(Arrays.stream(targets).map(SemanticCallGraphBuilderTest::type).toList());
    }

    private static RepositorySyntax syntax(MethodTarget target, ClassMetadata additionalType) {
        return syntax(List.of(type(target), additionalType));
    }

    private static RepositorySyntax syntax(List<ClassMetadata> types) {
        return new RepositorySyntax(List.of(), types);
    }

    private static ClassMetadata type(MethodTarget target) {
        return type(target, ClassMetadata.TypeKind.CLASS, true);
    }

    private static ClassMetadata interfaceType(MethodTarget target) {
        return type(target, ClassMetadata.TypeKind.INTERFACE, false);
    }

    private static ClassMetadata executableInterfaceType(MethodTarget target) {
        return type(target, ClassMetadata.TypeKind.INTERFACE, true);
    }

    private static ClassMetadata type(MethodTarget target, List<SyntaxInvocation> invocations) {
        SyntaxRange range = range(0, 0, 30, 0);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), ClassMetadata.TypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, true, invocations)), false, false, List.of(), range,
                new SourceSlice(range, "class " + target.className() + " {}"), false, List.of());
    }

    private static ClassMetadata annotatedType(MethodTarget target) {
        SyntaxRange range = range(0, 0, 30, 0);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), ClassMetadata.TypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, true, List.of(), "@Transactional\nvoid " + target.methodName() + "() {}")),
                false, false, List.of(), range, new SourceSlice(range, "class " + target.className() + " {}"),
                false, List.of());
    }

    private static ClassMetadata type(MethodTarget target, ClassMetadata.TypeKind kind, boolean executableDeclaration) {
        return typeWithMetadataPath(target, target.sourceFile(), kind, executableDeclaration);
    }

    private static ClassMetadata typeWithMetadataPath(
            MethodTarget target,
            String metadataPath,
            ClassMetadata.TypeKind kind,
            boolean executableDeclaration) {
        SyntaxRange range = range(0, 0, 30, 0);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                metadataPath, kind, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method(target, executableDeclaration)), false, false, List.of(), range,
                new SourceSlice(range, "class " + target.className() + " {}"), false, List.of());
    }

    private static MethodSignature method(MethodTarget target) {
        return method(target, true);
    }

    private static MethodSignature method(MethodTarget target, boolean executableDeclaration) {
        return method(target, executableDeclaration, List.of());
    }

    private static MethodSignature method(
            MethodTarget target,
            boolean executableDeclaration,
            List<SyntaxInvocation> invocations) {
        return method(target, executableDeclaration, invocations, "void " + target.methodName() + "() {}");
    }

    private static MethodSignature method(
            MethodTarget target,
            boolean executableDeclaration,
            List<SyntaxInvocation> invocations,
            String sourceText) {
        SyntaxRange range = range(0, 0, 5, 0);
        return new MethodSignature(
                target.methodName(), target.parameterTypes(), List.of(), null, null, 1, 6,
                range, new SourceSlice(range, sourceText),
                List.<TypeReference>of(), Optional.empty(), invocations, List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), executableDeclaration, true);
    }

    private static MethodTarget target(String className, String methodName) {
        return new MethodTarget(className + ".java", "com.example", className, methodName, List.of());
    }

    private static SemanticMethod method(MethodTarget target, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line + 2, 0));
        return new SemanticMethod(
                target.packageName(), target.className(), target.methodName(), target.parameterTypes(), "void",
                new SemanticLocation("file:///fixture/" + target.sourceFile(), range, range));
    }

    private static SemanticCall call(SemanticMethod target, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
        return new SemanticCall(Optional.of(target), target.methodName() + "()", List.of(range), false,
                SemanticResolutionOrigin.CALL_HIERARCHY);
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

        private final Map<SemanticMethod, List<SemanticCall>> outgoing = new HashMap<>();
        private final List<SemanticMethod> failingOutgoing = new java.util.ArrayList<>();
        private final List<SemanticMethod> failingImplementations = new java.util.ArrayList<>();
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
            failingImplementations.add(declaration);
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
            failingResolutions.put(callSite, new IllegalStateException("planned point resolution failure"));
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
        public List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            if (failingImplementations.contains(method)) {
                throw new IllegalStateException("planned implementation query failure");
            }
            return implementations.getOrDefault(method, List.of());
        }
    }
}
