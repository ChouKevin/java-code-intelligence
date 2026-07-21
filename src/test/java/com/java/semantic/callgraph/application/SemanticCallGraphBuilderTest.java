package com.java.semantic.callgraph.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.CallNode;
import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCallGraphBuilderTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture/orders"), RepositoryRevision.fixture());

    @Test
    void should_preserve_repeated_call_sites_as_distinct_edges() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        SemanticRange first = semanticRange(2, 4, 2, 10);
        SemanticRange second = semanticRange(4, 4, 4, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "work()", false, first, second));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(child)), root, 3);

        assertThat(result.graph().edges())
                .extracting(CallEdge::lineNumber)
                .containsExactly(3, 5);
    }

    @Test
    void should_use_snapshot_relative_call_site_when_syntax_metadata_is_missing(@TempDir Path temporaryDirectory)
            throws IOException {
        Path source = temporaryDirectory.resolve("src/main/java/Root.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Root {}");
        SemanticMethod root = methodAtPath("Root", "run", source, 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        RepositorySnapshot snapshot = new RepositorySnapshot(
                RepositoryId.of("orders"), temporaryDirectory, RepositoryRevision.fixture());
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "work()", false, semanticRange(2)));

        CallGraphBuildResult result = builder(semantic).build(snapshot, syntax(), root, 1);

        assertThat(result.graph().edges()).singleElement().satisfies(edge -> assertThat(edge.callSite()).isEqualTo(
                new CallSiteRange("src/main/java/Root.java", 3, 1, 3, 6)));
    }

    @Test
    void should_omit_call_site_when_semantic_uri_is_outside_snapshot(@TempDir Path temporaryDirectory)
            throws IOException {
        Path outside = Files.createTempFile("semantic-outside", ".java");
        SemanticMethod root = methodAtPath("Root", "run", outside, 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        RepositorySnapshot snapshot = new RepositorySnapshot(
                RepositoryId.of("orders"), temporaryDirectory, RepositoryRevision.fixture());
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "work()", false, semanticRange(2)));

        CallGraphBuildResult result = builder(semantic).build(snapshot, syntax(), root, 1);

        assertThat(result.graph().edges()).singleElement().satisfies(edge -> assertThat(edge.callSite()).isNull());
    }

    @Test
    void should_keep_overloads_as_distinct_nodes_and_use_exact_mapper_method_evidence() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod byString = method("OrderMapper", "find", List.of("String"), 10);
        SemanticMethod byLong = method("OrderMapper", "find", List.of("Long"), 20);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(
                root,
                call(byLong, "find(Long)", false, semanticRange(3)),
                call(byString, "find(String)", false, semanticRange(2)));
        ClassMetadata mapper = type(
                "OrderMapper", TypeKind.INTERFACE, List.of("Mapper"), false, List.of(), List.of(),
                syntaxMethod(byString, List.of(), "select string"),
                syntaxMethod(byLong, List.of(), ""));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), mapper), root, 1);

        assertThat(result.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId()))
                .extracting(node -> node.methodId().parameterTypes())
                .contains(List.of("String"), List.of("Long"));
        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .contains(ResolutionStrategy.MYBATIS_MAPPER, ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE);
    }

    @Test
    void should_emit_dfs_preorder_and_edges_in_canonical_target_then_range_order() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod zeta = method("Zeta", "work", List.of(), 20);
        SemanticMethod alpha = method("Alpha", "work", List.of(), 10);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(
                root,
                call(zeta, "zeta()", false, semanticRange(8)),
                call(alpha, "alpha()", false, semanticRange(6)));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(zeta), type(alpha)), root, 2);

        assertThat(result.graph().nodes()).extracting(CallNode::signature)
                .containsExactly("com.example.Root.run()", "com.example.Alpha.work()", "com.example.Zeta.work()");
        assertThat(result.graph().edges()).extracting(CallEdge::callExpression)
                .containsExactly("alpha()", "zeta()");
    }

    @Test
    void should_stop_only_current_path_cycles_while_reusing_nodes_on_other_branches() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod left = method("Left", "go", List.of(), 10);
        SemanticMethod right = method("Right", "go", List.of(), 20);
        SemanticMethod shared = method("Shared", "work", List.of(), 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(left, "left()", false, semanticRange(2)), call(right, "right()", false, semanticRange(3)))
                .outgoing(left, call(shared, "shared()", false, semanticRange(12)))
                .outgoing(right, call(shared, "shared()", false, semanticRange(22)))
                .outgoing(shared, call(root, "root()", false, semanticRange(32)));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(left), type(right), type(shared)), root, 5);

        assertThat(result.graph().nodes()).filteredOn(node -> Objects.nonNull(node.methodId())
                && "Shared".equals(node.methodId().className())).hasSize(1);
        CallNode rootNode = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()) && "Root".equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
        CallNode sharedNode = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()) && "Shared".equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
        assertThat(result.graph().nodes()).noneMatch(node -> CallType.CYCLE_BACK_EDGE.equals(node.callType()));
        assertThat(result.graph().edges()).filteredOn(edge -> edge.callee().equals(sharedNode.nodeId()))
                .hasSize(2);
        assertThat(result.graph().edges()).filteredOn(edge -> edge.callee().equals(rootNode.nodeId()))
                .singleElement();
        assertThat(semantic.outgoingRequests()).containsExactly(root, left, shared, right, shared);
    }

    @Test
    void should_apply_read_policy_before_inspecting_or_expanding_an_interface_target() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod forbiddenContract = method("SecretPaymentPort", "pay", List.of(), 10);
        SemanticMethod readableImplementation = method("ReadablePaymentService", "pay", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(forbiddenContract, "pay()", false, semanticRange(2)))
                .implementations(forbiddenContract, readableImplementation);
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return "SecretPaymentPort".equals(methodId.className())
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };

        CallGraphBuildResult result = builder(semantic, policy).build(
                SNAPSHOT,
                syntax(type(root), type(forbiddenContract, TypeKind.INTERFACE, List.of(), false),
                        type(readableImplementation)),
                root,
                2);

        assertThat(semantic.implementationRequests()).isEmpty();
        assertThat(result.graph().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.BUSINESS_READ_FORBIDDEN);
            assertThat(edge.visibility()).isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        });
        assertThat(result.graph().nodes()).noneMatch(node -> Objects.nonNull(node.methodId())
                && "ReadablePaymentService".equals(node.methodId().className()));
        assertThat(result.graph().nodes()).filteredOn(node -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                .equals(node.visibility())).singleElement().satisfies(node -> {
                    assertThat(node.methodId()).isNull();
                    assertThat(node.signature()).isEmpty();
                    assertThat(node.code()).isEmpty();
                });
    }

    @Test
    void should_omit_readable_method_source_when_recursive_parameter_or_return_type_is_business_forbidden() {
        SemanticMethod root = method("Root", "run", List.of("List<SecretDto>"), 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        TypeReference secretReference = new TypeReference(
                "SecretDto", "com.example.secret.SecretDto", List.of(), true);
        TypeReference secretList = new TypeReference(
                "List<SecretDto>", "java.util.List", List.of(secretReference), false);
        ClassMetadata rootMetadata = type(
                "Root", TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(root, List.of(), "", List.of(secretList), Optional.empty()));
        ClassMetadata childMetadata = type(
                "Child", TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(child, List.of(), "", List.of(), Optional.of(secretList)));
        ClassMetadata secretMetadata = typeInPackage("com.example.secret", "SecretDto");
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return "com.example.secret".equals(typeId.packageName())
                        && "SecretDto".equals(typeId.className())
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return "com.example.secret".equals(methodId.packageName())
                        && "SecretDto".equals(methodId.className())
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };

        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "child()", false, semanticRange(2)));
        CallGraphBuildResult result = builder(semantic, policy).build(
                SNAPSHOT, syntax(rootMetadata, childMetadata, secretMetadata), root, 2);

        assertThat(result.graph().nodes()).hasSize(2).allSatisfy(node -> {
            assertThat(node.visibility()).isEqualTo(EvidenceVisibility.READABLE);
            assertThat(node.code()).isEmpty();
        });
        assertThat(result.graph().toString()).doesNotContain("run source", "work source");
    }

    @Test
    void should_remove_readable_caller_annotation_when_outgoing_reference_is_business_forbidden()
            throws Exception {
        String annotationSentinel = "CALLER_FORBIDDEN_ANNOTATION_IDENTITY_SENTINEL";
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod forbidden = method("SecretService", "read", List.of(), 10);
        ClassMetadata rootMetadata = type(
                "Root", TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(
                        root, List.of(), "", List.of(), Optional.empty(), List.of(annotationSentinel)));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(forbidden, "forbidden()", false, semanticRange(2)));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return "SecretService".equals(methodId.className())
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic, policy).build(
                    SNAPSHOT, syntax(rootMetadata, type(forbidden)), root, 1);
            String serialized = new ObjectMapper().writeValueAsString(result);
            CallNode readableCaller = result.graph().nodes().stream()
                    .filter(node -> Objects.nonNull(node.methodId()))
                    .filter(node -> root.methodName().equals(node.methodId().methodName()))
                    .findFirst()
                    .orElseThrow();

            assertThat(readableCaller.methodId()).isEqualTo(new MethodId(
                    "orders", root.packageName(), root.className(), root.methodName(), root.parameterTypes()));
            assertThat(readableCaller.annotations()).isEmpty();
            assertThat(readableCaller.code()).isEmpty();
            assertThat(result.graph().legacyFlattened().methods()).singleElement().satisfies(method -> {
                assertThat(method.annotations()).isEmpty();
                assertThat(method.code()).isEmpty();
            });
            assertThat(result.graph().toString()).doesNotContain(annotationSentinel);
            assertThat(serialized).doesNotContain(annotationSentinel);
            assertThat(result.errors().toString()).doesNotContain(annotationSentinel);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_query_cutoff_once_for_sorted_immediate_signatures_without_recursing_grandchildren() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        SemanticMethod alpha = method("Alpha", "work", List.of(), 20);
        SemanticMethod zeta = method("Zeta", "work", List.of(), 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "child()", false, semanticRange(2)))
                .outgoing(child,
                        call(zeta, "zeta()", false, semanticRange(12)),
                        call(alpha, "alpha()", false, semanticRange(13)));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(child), type(alpha), type(zeta)), root, 1);

        assertThat(semantic.outgoingRequests()).containsExactly(root, child);
        assertThat(result.graph().nodes()).extracting(CallNode::callType)
                .containsExactly(CallType.INTERNAL_CLASS, CallType.TRAVERSAL_CUTOFF);
        assertThat(result.graph().nodes()).filteredOn(node -> CallType.TRAVERSAL_CUTOFF.equals(node.callType()))
                .singleElement()
                .satisfies(node -> assertThat(node.code()).isEqualTo(
                        "work source\n\nImmediate callees:\nalpha()\nzeta()"));
    }

    @Test
    void should_keep_expanded_and_cutoff_occurrences_distinct_regardless_of_discovery_order() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod expandedFirst = method("AlphaShared", "work", List.of(), 10);
        SemanticMethod expandedBranch = method("ZetaBranch", "work", List.of(), 20);
        FakeSemanticService expandedThenCutoff = new FakeSemanticService()
                .outgoing(root,
                        call(expandedFirst, "sharedExpanded()", false, semanticRange(2)),
                        call(expandedBranch, "branchAfter()", false, semanticRange(3)))
                .outgoing(expandedFirst)
                .outgoing(expandedBranch, call(expandedFirst, "sharedCutoff()", false, semanticRange(22)));

        CallGraphBuildResult expandedResult = builder(expandedThenCutoff).build(
                SNAPSHOT, syntax(type(root), type(expandedFirst), type(expandedBranch)), root, 2);

        SemanticMethod cutoffFirst = method("ZetaShared", "work", List.of(), 30);
        SemanticMethod cutoffBranch = method("AlphaBranch", "work", List.of(), 40);
        FakeSemanticService cutoffThenExpanded = new FakeSemanticService()
                .outgoing(root,
                        call(cutoffFirst, "sharedAfter()", false, semanticRange(2)),
                        call(cutoffBranch, "branchBefore()", false, semanticRange(3)))
                .outgoing(cutoffBranch, call(cutoffFirst, "sharedCutoff()", false, semanticRange(12)))
                .outgoing(cutoffFirst);

        CallGraphBuildResult cutoffResult = builder(cutoffThenExpanded).build(
                SNAPSHOT, syntax(type(root), type(cutoffFirst), type(cutoffBranch)), root, 2);

        assertThat(expandedResult.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "AlphaShared".equals(node.methodId().className()))
                .extracting(CallNode::callType)
                .containsExactly(CallType.INTERNAL_CLASS, CallType.TRAVERSAL_CUTOFF);
        assertThat(cutoffResult.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "ZetaShared".equals(node.methodId().className()))
                .extracting(CallNode::callType)
                .containsExactly(CallType.TRAVERSAL_CUTOFF, CallType.INTERNAL_CLASS);
        assertThat(expandedResult.graph().nodes())
                .filteredOn(node -> CallType.TRAVERSAL_CUTOFF.equals(node.callType()))
                .singleElement()
                .satisfies(node -> assertThat(node.code()).isEqualTo("work source"));
        assertThat(expandedResult.graph().edges()).filteredOn(edge -> expandedResult.graph().nodes().stream()
                .filter(node -> node.nodeId().equals(edge.caller()))
                .anyMatch(node -> CallType.TRAVERSAL_CUTOFF.equals(node.callType()))).isEmpty();
        assertThat(expandedResult.graph().legacyFlattened().methods())
                .filteredOn(method -> CallType.TRAVERSAL_CUTOFF.equals(method.callType()))
                .singleElement().satisfies(method -> assertThat(method.callees()).isEmpty());
    }

    @Test
    void should_publish_throwing_cutoff_definition_fallback_at_its_exact_occurrence_and_keep_later_evidence()
            throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod cutoff = method("Cutoff", "work", List.of(), 10);
        SemanticMethod immediate = method("Immediate", "work", List.of(), 20);
        SyntaxInvocation failed = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(14, 2, 14, 9), "failed()", "port", "", "",
                Optional.empty(), new SyntaxPosition(14, 2));
        SyntaxInvocation succeeding = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(14, 12, 14, 22), "succeeding()", "port", "", "",
                Optional.empty(), new SyntaxPosition(14, 12));
        SemanticRange failedRange = semanticRange(failed.range());
        SemanticRange succeedingRange = semanticRange(succeeding.range());
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(cutoff, "cutoff()", false, semanticRange(2)))
                .outgoing(cutoff)
                .failDefinition(
                        cutoff, failedRange,
                        new IllegalStateException(
                                "CUTOFF_DEFINITION_EXCEPTION_SENTINEL CUTOFF_DEFINITION_LOG_SENTINEL"))
                .definition(
                        cutoff, succeedingRange,
                        call(immediate, "succeeding()", false, succeedingRange));
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT, syntax(type(root), type(cutoff, failed, succeeding), type(immediate)), root, 1);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(result.partial()).isTrue();
            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
                assertThat(error.detail()).isEqualTo("IllegalStateException");
            });
            assertThat(semantic.outgoingRequests()).containsExactly(root, cutoff);
            assertThat(semantic.definitionRequests()).containsExactly(failedRange, succeedingRange);
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId())
                            && "Cutoff".equals(node.methodId().className()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.code()).isEqualTo(
                            "work source\n\nImmediate callees:\nsucceeding()"));
            assertThat(result.graph().edges())
                    .filteredOn(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.sourceFile()).isEqualTo("Cutoff.java");
                        assertThat(edge.lineNumber()).isEqualTo(15);
                        assertThat(result.graph().nodes())
                                .filteredOn(node -> node.nodeId().equals(edge.callee()))
                                .singleElement()
                                .satisfies(node -> {
                                    assertThat(node.methodId()).isNull();
                                    assertThat(node.signature()).isEmpty();
                                    assertThat(node.annotations()).isEmpty();
                                    assertThat(node.code()).isEmpty();
                                });
                    });
            assertThat(result.graph().toString()).doesNotContain(
                    "CUTOFF_DEFINITION_EXCEPTION_SENTINEL", "CUTOFF_DEFINITION_LOG_SENTINEL");
            assertThat(serialized).doesNotContain(
                    "CUTOFF_DEFINITION_EXCEPTION_SENTINEL", "CUTOFF_DEFINITION_LOG_SENTINEL");
            assertThat(result.errors().toString()).doesNotContain(
                    "CUTOFF_DEFINITION_EXCEPTION_SENTINEL", "CUTOFF_DEFINITION_LOG_SENTINEL");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                        .doesNotContain(
                                "CUTOFF_DEFINITION_EXCEPTION_SENTINEL", "CUTOFF_DEFINITION_LOG_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_publish_cutoff_conversion_failure_as_an_unresolved_occurrence_and_keep_valid_evidence() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod cutoff = method("Cutoff", "work", List.of(), 10);
        SemanticMethod immediate = method("Immediate", "work", List.of(), 20);
        SemanticRange failedRange = semanticRange(14, 2, 14, 9);
        SemanticRange succeedingRange = semanticRange(14, 12, 14, 22);
        SemanticCall conversionFailed = new SemanticCall(
                Optional.empty(), "semantic target identity unavailable", List.of(failedRange), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.CONVERSION_FAILED);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(cutoff, "cutoff()", false, semanticRange(2)))
                .outgoing(cutoff,
                        conversionFailed,
                        call(immediate, "succeeding()", false, succeedingRange));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(cutoff), type(immediate)), root, 1);

        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
            assertThat(error.detail()).isEqualTo("TARGET_CONVERSION_FAILED");
        });
        assertThat(result.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "Cutoff".equals(node.methodId().className()))
                .singleElement()
                .satisfies(node -> assertThat(node.code()).isEqualTo(
                        "work source\n\nImmediate callees:\nsucceeding()"));
        assertThat(result.graph().edges())
                .filteredOn(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.sourceFile()).isEqualTo("Cutoff.java");
                    assertThat(edge.lineNumber()).isEqualTo(15);
                    assertThat(edge.callSite()).isEqualTo(new CallSiteRange("Cutoff.java", 15, 3, 15, 10));
                    assertThat(edge.callExpression()).isEqualTo("semantic target identity unavailable");
                    assertThat(result.graph().nodes())
                            .filteredOn(node -> node.nodeId().equals(edge.callee()))
                            .singleElement()
                            .satisfies(node -> {
                                assertThat(node.methodId()).isNull();
                                assertThat(node.signature()).isEmpty();
                                assertThat(node.code()).isEmpty();
                            });
                });
    }

    @Test
    void should_memoize_cutoff_definition_failure_and_publish_an_exact_diagnostic_for_each_occurrence()
            throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod cutoff = method("Cutoff", "work", List.of(), 10);
        SemanticMethod immediate = method("Immediate", "work", List.of(), 20);
        SyntaxInvocation failed = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(14, 2, 14, 9), "failed()", "port", "", "",
                Optional.empty(), new SyntaxPosition(14, 2));
        SyntaxInvocation succeeding = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(14, 12, 14, 22), "succeeding()", "port", "", "",
                Optional.empty(), new SyntaxPosition(14, 12));
        SemanticRange failedRange = semanticRange(failed.range());
        SemanticRange succeedingRange = semanticRange(succeeding.range());
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(cutoff, "cutoff()", false, semanticRange(2), semanticRange(3)))
                .outgoing(cutoff)
                .failDefinition(
                        cutoff, failedRange,
                        new IllegalStateException("MEMOIZED_CUTOFF_DEFINITION_FAILURE_SENTINEL"))
                .definition(
                        cutoff, succeedingRange,
                        call(immediate, "succeeding()", false, succeedingRange));
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT, syntax(type(root), type(cutoff, failed, succeeding), type(immediate)), root, 1);

            assertThat(semantic.outgoingRequests()).containsExactly(root, cutoff);
            assertThat(semantic.definitionRequests()).containsExactly(failedRange, succeedingRange);
            assertThat(result.errors()).singleElement();
            assertThat(result.graph().edges())
                    .filteredOn(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                    .hasSize(2)
                    .allSatisfy(edge -> assertThat(edge.lineNumber()).isEqualTo(15));
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId())
                            && "Cutoff".equals(node.methodId().className()))
                    .hasSize(2)
                    .allSatisfy(node -> assertThat(node.code()).isEqualTo(
                            "work source\n\nImmediate callees:\nsucceeding()"));
            assertThat(result.graph().nodes()).hasSize(5);
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.isNull(node.methodId())
                            || !root.className().equals(node.methodId().className()))
                    .allSatisfy(node -> assertThat(result.graph().edges())
                            .extracting(CallEdge::callee)
                            .contains(node.nodeId()));
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .doesNotContain("MEMOIZED_CUTOFF_DEFINITION_FAILURE_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_omit_cutoff_source_and_signatures_when_immediate_target_is_business_forbidden() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod child = method("Child", "work", List.of(), 10);
        SemanticMethod forbidden = method("SecretService", "read", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(child, "child()", false, semanticRange(2)))
                .outgoing(child, call(
                        forbidden, "SECRET_CALL_SENTINEL()", false, semanticRange(12)));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return "SecretService".equals(methodId.className())
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };

        CallGraphBuildResult result = builder(semantic, policy).build(
                SNAPSHOT, syntax(type(root), type(child), type(forbidden)), root, 1);

        assertThat(semantic.outgoingRequests()).containsExactly(root, child);
        assertThat(result.graph().nodes()).filteredOn(node -> CallType.TRAVERSAL_CUTOFF.equals(node.callType()))
                .singleElement()
                .satisfies(node -> assertThat(node.code()).isEmpty());
        assertThat(result.graph().toString()).doesNotContain("work source", "SECRET_CALL_SENTINEL");
    }

    @Test
    void should_apply_opaque_semantics_before_depth_cutoff_without_querying_targets() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod external = method("External", "send", List.of(), 10);
        SemanticMethod feign = method("InventoryClient", "fetch", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(external, "external()", true, semanticRange(2)),
                        call(feign, "fetch()", false, semanticRange(3)))
                .failOutgoing(external, new IllegalStateException("external target must not be queried"))
                .failOutgoing(feign, new IllegalStateException("Feign target must not be queried"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(feign, TypeKind.INTERFACE, List.of("FeignClient"), false)), root, 1);

        assertThat(result.partial()).isFalse();
        assertThat(result.errors()).isEmpty();
        assertThat(semantic.outgoingRequests()).containsExactly(root);
        assertThat(result.graph().nodes()).extracting(CallNode::callType)
                .contains(CallType.EXTERNAL_LIB, CallType.RPC_CLIENT)
                .doesNotContain(CallType.TRAVERSAL_CUTOFF, CallType.UNRESOLVED);
    }

    @Test
    void should_not_recurse_external_feign_or_targetless_calls() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod external = method("External", "send", List.of(), 10);
        SemanticMethod feign = method("InventoryClient", "fetch", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(
                root,
                call(external, "external()", true, semanticRange(2)),
                call(feign, "fetch()", false, semanticRange(3)),
                unresolvedCall("opaque()", true, semanticRange(4)));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(feign, TypeKind.INTERFACE, List.of("FeignClient"), false)), root, 4);

        assertThat(semantic.outgoingRequests()).containsExactly(root);
        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .contains(ResolutionStrategy.EXTERNAL_LIBRARY, ResolutionStrategy.FEIGN_CLIENT,
                        ResolutionStrategy.UNRESOLVED_TARGET);
    }

    @Test
    void should_attach_llm_safe_warning_to_each_opaque_ambiguous_and_unresolved_edge() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod external = method("External", "send", List.of(), 10);
        SemanticMethod feign = method("InventoryClient", "fetch", List.of(), 20);
        SemanticMethod contract = method("PaymentPort", "pay", List.of(), 30);
        SemanticMethod alpha = method("AlphaPayment", "pay", List.of(), 40);
        SemanticMethod beta = method("BetaPayment", "pay", List.of(), 50);
        SyntaxInvocation contractInvocation = invocation("port.pay()", 5, "", 5);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(external, "external()", true, semanticRange(2)),
                        call(feign, "fetch()", false, semanticRange(3)),
                        unresolvedCall("missing()", false, semanticRange(4)),
                        call(contract, "pay()", false, semanticRange(contractInvocation.range())))
                .implementations(contract, beta, alpha);

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT,
                syntax(type(root, contractInvocation),
                        type(feign, TypeKind.INTERFACE, List.of("FeignClient"), false),
                        type(contract, TypeKind.INTERFACE, List.of(), false), type(alpha), type(beta)),
                root,
                3);

        assertThat(result.graph().edges())
                .filteredOn(edge -> ResolutionStrategy.EXTERNAL_LIBRARY.equals(edge.resolutionStrategy()))
                .singleElement().satisfies(edge -> assertThat(edge.warnings()).singleElement()
                        .asString().contains("body unavailable", "do not infer"));
        assertThat(result.graph().edges())
                .filteredOn(edge -> ResolutionStrategy.FEIGN_CLIENT.equals(edge.resolutionStrategy()))
                .singleElement().satisfies(edge -> assertThat(edge.warnings()).singleElement()
                        .asString().contains("body unavailable", "do not infer"));
        assertThat(result.graph().edges())
                .filteredOn(edge -> ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES.equals(edge.resolutionStrategy()))
                .hasSize(2).allSatisfy(edge -> assertThat(edge.warnings()).singleElement()
                        .asString().contains("Multiple Spring implementations", "proven target"));
        assertThat(result.graph().edges())
                .filteredOn(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                .singleElement().satisfies(edge -> assertThat(edge.warnings())
                        .anySatisfy(warning -> assertThat(warning).contains("identity is not proven", "as fact")));
    }

    @Test
    void should_classify_build_only_on_nested_builder_declaring_class_with_annotated_outer() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod outerBuild = methodWithReturn("OrderDto", "build", List.of(), "OrderDto", 10);
        SemanticMethod nestedBuild = methodWithReturn(
                "OrderDto.OrderDtoBuilder", "build", List.of(), "OrderDto", 20);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(
                root,
                call(outerBuild, "outer.build()", false, semanticRange(2)),
                call(nestedBuild, "nested.build()", false, semanticRange(3)));
        ClassMetadata outer = type(
                "OrderDto", TypeKind.CLASS, List.of("lombok.Builder"), false, List.of(), List.of(),
                syntaxMethod(outerBuild, List.of(), ""));
        ClassMetadata nested = type(
                "OrderDto.OrderDtoBuilder", TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(nestedBuild, List.of(), ""));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), outer, nested), root, 2);

        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .containsExactly(ResolutionStrategy.JDT_CALL_HIERARCHY, ResolutionStrategy.LOMBOK_GENERATED);
        assertThat(result.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "OrderDto".equals(node.methodId().className()))
                .singleElement()
                .extracting(CallNode::callType)
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(result.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "OrderDto.OrderDtoBuilder".equals(node.methodId().className()))
                .singleElement()
                .extracting(CallNode::callType)
                .isEqualTo(CallType.GENERATED_CODE);
    }

    @Test
    void should_not_classify_nested_builder_when_semantic_return_contradicts_owner() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod wrongBuild = methodWithReturn(
                "OrderDto.OrderDtoBuilder", "build", List.of(), "OtherDto", 20);
        FakeSemanticService semantic = new FakeSemanticService().outgoing(
                root, call(wrongBuild, "nested.build()", false, semanticRange(3)));
        ClassMetadata outer = type(
                "OrderDto", TypeKind.CLASS, List.of("lombok.Builder"), false, List.of(), List.of());
        ClassMetadata nested = type(
                "OrderDto.OrderDtoBuilder", TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(wrongBuild, List.of(), ""));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), outer, nested), root, 2);

        assertThat(result.graph().edges()).singleElement()
                .extracting(CallEdge::resolutionStrategy)
                .isEqualTo(ResolutionStrategy.JDT_CALL_HIERARCHY);
        assertThat(result.graph().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId())
                        && "OrderDto.OrderDtoBuilder".equals(node.methodId().className()))
                .singleElement()
                .extracting(CallNode::callType)
                .isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_use_definition_fallback_only_for_syntax_sites_uncovered_by_hierarchy() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod hierarchy = method("Hierarchy", "work", List.of(), 10);
        SemanticMethod fallback = method("Fallback", "work", List.of(), 20);
        SyntaxInvocation covered = invocation("hierarchy()", 2, "", 0);
        SyntaxInvocation missing = invocation("fallback()", 3, "", 0);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(hierarchy, "hierarchy()", false, semanticRange(covered.range())))
                .definition(root, semanticRange(missing.range()), call(fallback, "fallback()", false, semanticRange(missing.range())));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root, covered, missing), type(hierarchy), type(fallback)), root, 1);

        assertThat(semantic.definitionRequests()).containsExactly(semanticRange(missing.range()));
        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .containsExactly(ResolutionStrategy.JDT_DEFINITION_FALLBACK, ResolutionStrategy.JDT_CALL_HIERARCHY);
    }

    @Test
    void should_query_definition_with_syntax_anchor_while_preserving_full_call_range() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SyntaxInvocation missing = new SyntaxInvocation(
                InvocationKind.CONSTRUCTOR,
                syntaxRange(3, 2, 3, 18),
                "new Created(value)",
                "",
                "",
                "",
                Optional.empty(),
                new SyntaxPosition(3, 6));
        FakeSemanticService semantic = new FakeSemanticService();

        builder(semantic).build(SNAPSHOT, syntax(type(root, missing)), root, 2);

        assertThat(semantic.definitionCallSites()).singleElement().satisfies(callSite -> {
            assertThat(callSite.range()).isEqualTo(semanticRange(missing.range()));
            assertThat(callSite.anchor()).isEqualTo(new SemanticPosition(3, 6));
        });
    }

    @Test
    void should_preserve_lambda_body_call_without_querying_or_emitting_the_lambda_expression(
            @TempDir Path temporaryDirectory) throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Root.java"), """
                package com.example;

                class Root {
                    void run() {
                        Runnable task = () -> helper();
                    }

                    void helper() {
                    }
                }
                """);
        RepositorySyntax extracted = new JdtSyntaxExtractionService().extract(temporaryDirectory);
        MethodSignature runSyntax = extracted.classes().stream()
                .filter(type -> "com.example.Root".equals(type.fullyQualifiedName()))
                .flatMap(type -> type.methods().stream())
                .filter(method -> "run".equals(method.name()))
                .findFirst()
                .orElseThrow();
        assertThat(runSyntax.invocations())
                .noneMatch(invocation -> InvocationKind.LAMBDA.equals(invocation.kind()));
        SyntaxInvocation bodyCall = runSyntax.invocations().stream()
                .filter(invocation -> "helper()".equals(invocation.expression()))
                .findFirst()
                .orElseThrow();
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod helper = method("Root", "helper", List.of(), 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root)
                .definition(root, semanticRange(bodyCall.range()), call(
                        helper, "helper() : void", false, semanticRange(bodyCall.range())))
                .outgoing(helper);

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, extracted, root, 2);

        assertThat(semantic.definitionRequests()).containsExactly(semanticRange(bodyCall.range()));
        assertThat(result.graph().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.callExpression()).isEqualTo("helper()");
            assertThat(result.graph().nodes())
                    .filteredOn(node -> node.nodeId().equals(edge.callee()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.methodId().methodName()).isEqualTo("helper"));
        });
        assertThat(result.graph().edges()).noneMatch(edge -> edge.caller().equals(edge.callee()));
    }

    @Test
    void should_emit_explicit_unresolved_evidence_when_definition_fallback_finds_no_target() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SyntaxInvocation missing = invocation("missing.call()", 3, "", 6);
        FakeSemanticService semantic = new FakeSemanticService();

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root, missing)), root, 2);

        assertThat(semantic.definitionRequests()).containsExactly(semanticRange(missing.range()));
        assertThat(result.graph().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET);
            assertThat(edge.callExpression()).isEqualTo("missing.call()");
        });
        CallNode unresolved = result.graph().nodes().stream()
                .filter(node -> CallType.UNRESOLVED.equals(node.callType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.graph().edges().getFirst().callee()).isEqualTo(unresolved.nodeId());
        assertThat(semantic.outgoingRequests()).containsExactly(root);
    }

    @Test
    void should_preserve_raw_signature_and_full_distinct_ranges_for_targetless_unresolved_calls()
            throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SyntaxInvocation first = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(3, 2, 3, 9), "missing()", "port", "", "",
                Optional.empty(), new SyntaxPosition(3, 2));
        SyntaxInvocation second = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(3, 14, 3, 21), "missing()", "port", "", "",
                Optional.empty(), new SyntaxPosition(3, 14));

        CallGraphBuildResult result = builder(new FakeSemanticService()).build(
                SNAPSHOT, syntax(type(root, first, second)), root, 2);
        String serialized = new ObjectMapper().writeValueAsString(result.graph());

        assertThat(result.graph().edges()).hasSize(2);
        assertThat(result.graph().edges()).extracting(CallEdge::callSite).containsExactly(
                new CallSiteRange("Root.java", 4, 3, 4, 10),
                new CallSiteRange("Root.java", 4, 15, 4, 22));
        assertThat(result.graph().edges()).allSatisfy(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET);
            assertThat(edge.evidence()).containsExactly("missing()");
            assertThat(result.graph().nodes())
                    .filteredOn(node -> node.nodeId().equals(edge.callee()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.methodId()).isNull());
        });
        assertThat(serialized).contains("callSite", "startLine", "startCharacter", "endLine", "endCharacter")
                .doesNotContain("file:///fixture/Root.java");
    }

    @Test
    void should_opaque_binding_proven_forbidden_unresolved_occurrences_and_retain_safe_sibling()
            throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod safe = method("SafeService", "work", List.of(), 10);
        InvocationTarget forbiddenTarget = new InvocationTarget(
                "com.example.secret", "VaultService", "open", List.of());
        SyntaxInvocation targetless = invocation(
                "SECRET_TARGETLESS_CALL()", syntaxRange(3, 2, 3, 26), forbiddenTarget,
                new SyntaxPosition(3, 2));
        SyntaxInvocation failingFallback = invocation(
                "SECRET_FALLBACK_CALL()", syntaxRange(4, 2, 4, 25), forbiddenTarget,
                new SyntaxPosition(4, 2));
        SyntaxInvocation safeInvocation = invocation(
                "safe.work()", syntaxRange(5, 2, 5, 13), null, new SyntaxPosition(5, 7));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        unresolvedCall("SECRET_TARGETLESS_SIGNATURE", false, semanticRange(targetless.range())),
                        call(safe, "safe.work()", false, semanticRange(safeInvocation.range())))
                .failDefinition(root, semanticRange(failingFallback.range()), new IllegalStateException(
                        "SECRET_FALLBACK_EXCEPTION_SENTINEL"));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return typeId.packageName().startsWith("com.example.secret")
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return methodId.packageName().startsWith("com.example.secret")
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic, policy).build(
                    SNAPSHOT, syntax(type(root, targetless, failingFallback, safeInvocation), type(safe)), root, 2);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(result.partial()).isTrue();
            assertThat(result.graph().edges()).filteredOn(edge ->
                    EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(edge.visibility()))
                    .hasSize(2)
                    .allSatisfy(edge -> {
                        assertThat(edge.callExpression()).isEmpty();
                        assertThat(edge.callSite()).isNull();
                        assertThat(edge.evidence()).isEmpty();
                        assertThat(edge.warnings()).containsExactly(RestrictedEvidenceRedactor.GENERIC_WARNING);
                        assertThat(edge.resolutionStrategy())
                                .isEqualTo(ResolutionStrategy.BUSINESS_READ_FORBIDDEN);
                    });
            assertThat(result.graph().nodes()).filteredOn(node ->
                    EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility()))
                    .hasSize(2)
                    .allSatisfy(node -> assertThat(node.methodId()).isNull());
            assertThat(result.graph().legacyFlattened().methods())
                    .filteredOn(node -> "SafeService".equals(node.className()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.code()).isEqualTo("work source"));
            assertThat(result.graph().legacyFlattened().toString()).doesNotContain(
                    "SECRET_TARGETLESS_CALL", "SECRET_TARGETLESS_SIGNATURE", "SECRET_FALLBACK_CALL",
                    "SECRET_FALLBACK_EXCEPTION_SENTINEL", "VaultService");
            assertThat(result.graph().toString()).doesNotContain(
                    "SECRET_TARGETLESS_CALL", "SECRET_TARGETLESS_SIGNATURE", "SECRET_FALLBACK_CALL",
                    "SECRET_FALLBACK_EXCEPTION_SENTINEL", "VaultService");
            assertThat(serialized).doesNotContain(
                    "SECRET_TARGETLESS_CALL", "SECRET_TARGETLESS_SIGNATURE", "SECRET_FALLBACK_CALL",
                    "SECRET_FALLBACK_EXCEPTION_SENTINEL", "VaultService");
            assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("SECRET_FALLBACK_EXCEPTION_SENTINEL"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_emit_all_ambiguous_spring_candidates_and_warning() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod contract = method("PaymentPort", "pay", List.of(), 10);
        SemanticMethod alpha = method("AlphaPayment", "pay", List.of(), 20);
        SemanticMethod beta = method("BetaPayment", "pay", List.of(), 30);
        SyntaxInvocation invocation = invocation("port.pay()", 2, "", 5);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(contract, "pay()", false, semanticRange(invocation.range())))
                .implementations(contract, beta, alpha);

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT,
                syntax(type(root, invocation), type(contract, TypeKind.INTERFACE, List.of(), false),
                        type(alpha), type(beta)),
                root,
                1);

        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .containsExactly(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES,
                        ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES);
        assertThat(result.graph().edges()).extracting(edge -> edge.callee().value())
                .containsExactly("node-2", "node-3");
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void should_treat_empty_implementation_candidates_as_unresolved_target() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod contract = method("PaymentPort", "pay", List.of(), 10);
        SyntaxInvocation invocation = invocation("port.pay()", 2, "", 5);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(contract, "pay()", false, semanticRange(invocation.range())))
                .implementations(contract);

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root, invocation), type(contract, TypeKind.INTERFACE, List.of(), false)), root, 2);

        assertThat(result.graph().edges()).singleElement()
                .extracting(CallEdge::resolutionStrategy).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET);
        assertThat(result.warnings()).noneMatch(warning -> warning.message().contains("multiple implementations"));
    }

    @Test
    void should_isolate_root_interface_implementation_failure_to_exact_occurrence() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod before = method("AlphaBefore", "work", List.of(), 10);
        SemanticMethod contract = method("MiddleContract", "work", List.of(), 20);
        SemanticMethod after = method("ZetaAfter", "work", List.of(), 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(before, "before()", false, semanticRange(2)),
                        call(contract, "contract.call()", false, semanticRange(3)),
                        call(after, "after()", false, semanticRange(4)))
                .failImplementations(
                        contract,
                        new IllegalStateException(
                                "IMPLEMENTATION_EXCEPTION_SENTINEL IMPLEMENTATION_LOG_SENTINEL"));
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT,
                    syntax(
                            type(root),
                            type(before),
                            type(contract, TypeKind.INTERFACE, List.of(), false),
                            type(after)),
                    root,
                    3);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(result.partial()).isTrue();
            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
                assertThat(error.detail()).isEqualTo("IllegalStateException");
            });
            assertThat(semantic.outgoingRequests()).containsExactly(root, before, after);
            assertThat(semantic.implementationRequests()).containsExactly(contract);
            assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                    .containsExactly(
                            ResolutionStrategy.JDT_CALL_HIERARCHY,
                            ResolutionStrategy.UNRESOLVED_TARGET,
                            ResolutionStrategy.JDT_CALL_HIERARCHY);
            CallEdge failedEdge = result.graph().edges().get(1);
            assertThat(failedEdge.callExpression()).isEqualTo("contract.call()");
            assertThat(result.graph().nodes())
                    .filteredOn(node -> node.nodeId().equals(failedEdge.callee()))
                    .singleElement()
                    .satisfies(node -> {
                        assertThat(node.callType()).isEqualTo(CallType.UNRESOLVED);
                        assertThat(node.code()).isEmpty();
                    });
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId()))
                    .filteredOn(node -> "AlphaBefore".equals(node.methodId().className())
                            || "ZetaAfter".equals(node.methodId().className()))
                    .hasSize(2)
                    .allSatisfy(node -> assertThat(node.code()).isEqualTo("work source"));
            assertThat(result.toString()).doesNotContain(
                    "IMPLEMENTATION_EXCEPTION_SENTINEL", "IMPLEMENTATION_LOG_SENTINEL");
            assertThat(serialized).doesNotContain(
                    "IMPLEMENTATION_EXCEPTION_SENTINEL", "IMPLEMENTATION_LOG_SENTINEL");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                        .doesNotContain(
                                "IMPLEMENTATION_EXCEPTION_SENTINEL", "IMPLEMENTATION_LOG_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_isolate_throwing_definition_fallback_to_exact_full_range_and_continue() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod parent = method("Parent", "work", List.of(), 10);
        SemanticMethod before = method("AlphaFallback", "work", List.of(), 10);
        SemanticMethod after = method("ZetaFallback", "work", List.of(), 20);
        SyntaxInvocation first = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(4, 2, 4, 9), "shared()", "port", "", "",
                Optional.empty(), new SyntaxPosition(4, 2));
        SyntaxInvocation failing = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(4, 12, 4, 20), "shared()", "port", "", "",
                Optional.empty(), new SyntaxPosition(4, 12));
        SyntaxInvocation later = new SyntaxInvocation(
                InvocationKind.METHOD, syntaxRange(4, 24, 4, 31), "shared()", "port", "", "",
                Optional.empty(), new SyntaxPosition(4, 24));
        SemanticRange firstRange = semanticRange(first.range());
        SemanticRange failingRange = semanticRange(failing.range());
        SemanticRange laterRange = semanticRange(later.range());
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(parent, "parent()", false, semanticRange(2)))
                .definition(parent, firstRange, call(before, "shared()", false, firstRange))
                .failDefinition(
                        parent,
                        failingRange,
                        new IllegalArgumentException(
                                "DEFINITION_EXCEPTION_SENTINEL DEFINITION_LOG_SENTINEL"))
                .definition(parent, laterRange, call(after, "shared()", false, laterRange));
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT,
                    syntax(type(root), type(parent, first, failing, later), type(before), type(after)),
                    root,
                    3);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(result.partial()).isTrue();
            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
                assertThat(error.detail()).isEqualTo("IllegalArgumentException");
            });
            assertThat(semantic.definitionRequests())
                    .containsExactly(firstRange, failingRange, laterRange);
            assertThat(semantic.outgoingRequests()).containsExactly(root, parent, before, after);
            assertThat(result.graph().edges())
                    .filteredOn(edge -> ResolutionStrategy.JDT_DEFINITION_FALLBACK.equals(
                            edge.resolutionStrategy()))
                    .hasSize(2);
            assertThat(result.graph().edges())
                    .filteredOn(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(
                            edge.resolutionStrategy()))
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.callExpression()).isEqualTo("shared()");
                        assertThat(edge.lineNumber()).isEqualTo(5);
                        assertThat(result.graph().nodes())
                                .filteredOn(node -> node.nodeId().equals(edge.callee()))
                                .singleElement()
                                .satisfies(node -> {
                                    assertThat(node.callType()).isEqualTo(CallType.UNRESOLVED);
                                    assertThat(node.code()).isEmpty();
                                });
                    });
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId()))
                    .extracting(node -> node.methodId().className())
                    .contains("AlphaFallback", "ZetaFallback");
            assertThat(result.toString()).doesNotContain(
                    "DEFINITION_EXCEPTION_SENTINEL", "DEFINITION_LOG_SENTINEL");
            assertThat(serialized).doesNotContain(
                    "DEFINITION_EXCEPTION_SENTINEL", "DEFINITION_LOG_SENTINEL");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalArgumentException")
                        .doesNotContain(
                                "DEFINITION_EXCEPTION_SENTINEL", "DEFINITION_LOG_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_preserve_successful_sibling_when_one_child_query_fails() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod failing = method("Failing", "work", List.of(), 10);
        SemanticMethod successful = method("Successful", "work", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(failing, "failing()", false, semanticRange(2)),
                        call(successful, "successful()", false, semanticRange(3)))
                .failOutgoing(failing, new IllegalStateException("child unavailable"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(failing), type(successful)), root, 2);

        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .contains(ResolutionStrategy.UNRESOLVED_TARGET, ResolutionStrategy.JDT_CALL_HIERARCHY);
        CallEdge failedEdge = result.graph().edges().stream()
                .filter(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                .findFirst()
                .orElseThrow();
        CallNode failedNode = result.graph().nodes().stream()
                .filter(node -> node.nodeId().equals(failedEdge.callee()))
                .findFirst()
                .orElseThrow();
        assertThat(failedNode.callType()).isEqualTo(CallType.UNRESOLVED);
        assertThat(failedNode.methodId()).isNotNull();
        assertThat(String.join(" ", result.errors().stream()
                .map(error -> error.message() + " " + error.detail())
                .toList())).doesNotContain("child unavailable");
        assertThat(result.graph().nodes()).allSatisfy(node -> {
            assertThat(node.signature()).doesNotContain("child unavailable");
            assertThat(node.code()).doesNotContain("child unavailable");
        });
        assertThat(semantic.outgoingRequests()).containsExactly(root, failing, successful);
    }

    @Test
    void should_isolate_cutoff_evidence_query_failure_and_continue_successful_sibling() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod failing = method("FailingCutoff", "work", List.of(), 10);
        SemanticMethod successful = method("SuccessfulCutoff", "work", List.of(), 20);
        SemanticMethod immediate = method("Immediate", "call", List.of(), 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(failing, "failing()", false, semanticRange(2)),
                        call(successful, "successful()", false, semanticRange(3)))
                .failOutgoing(failing, new IllegalStateException(
                        "CUTOFF_EXCEPTION_SENTINEL CUTOFF_LOG_SENTINEL"))
                .outgoing(successful, call(immediate, "immediate()", false, semanticRange(22)));
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT, syntax(type(root), type(failing), type(successful), type(immediate)), root, 1);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(result.partial()).isTrue();
            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
                assertThat(error.detail()).isEqualTo("IllegalStateException");
            });
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId())
                            && "SuccessfulCutoff".equals(node.methodId().className()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.code())
                            .isEqualTo("work source\n\nImmediate callees:\nimmediate()"));
            assertThat(result.graph().nodes())
                    .filteredOn(node -> Objects.nonNull(node.methodId())
                            && "FailingCutoff".equals(node.methodId().className()))
                    .singleElement()
                    .satisfies(node -> {
                        assertThat(node.callType()).isEqualTo(CallType.UNRESOLVED);
                        assertThat(node.code()).isEmpty();
                    });
            assertThat(semantic.outgoingRequests()).containsExactly(root, failing, successful);
            assertThat(result.toString()).doesNotContain(
                    "CUTOFF_EXCEPTION_SENTINEL", "CUTOFF_LOG_SENTINEL");
            assertThat(serialized).doesNotContain(
                    "CUTOFF_EXCEPTION_SENTINEL", "CUTOFF_LOG_SENTINEL");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                        .doesNotContain("CUTOFF_EXCEPTION_SENTINEL", "CUTOFF_LOG_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_memoize_failed_cutoff_query_and_emit_edge_local_unresolved_occurrences() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod failing = method("FailingCutoff", "work", List.of(), 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(
                        failing, "failing()", false, semanticRange(2), semanticRange(3)))
                .failOutgoing(failing, new IllegalStateException("REPEATED_FAILURE_SENTINEL"));

        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CallGraphBuildResult result = builder(semantic).build(
                    SNAPSHOT, syntax(type(root), type(failing)), root, 1);
            String serialized = new ObjectMapper().writeValueAsString(result);

            assertThat(semantic.outgoingRequests()).containsExactly(root, failing);
            assertThat(result.partial()).isTrue();
            assertThat(result.errors()).singleElement();
            assertThat(result.graph().edges()).hasSize(2).allSatisfy(edge ->
                    assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET));
            assertThat(result.graph().edges()).extracting(CallEdge::callee).doesNotHaveDuplicates();
            assertThat(result.graph().nodes())
                    .filteredOn(node -> CallType.UNRESOLVED.equals(node.callType()))
                    .hasSize(2);
            assertThat(result.toString()).doesNotContain("REPEATED_FAILURE_SENTINEL");
            assertThat(serialized).doesNotContain("REPEATED_FAILURE_SENTINEL");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                        .doesNotContain("REPEATED_FAILURE_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_keep_shared_readable_node_when_later_cutoff_occurrence_fails() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod shared = method("AlphaShared", "work", List.of(), 10);
        SemanticMethod branch = method("BetaBranch", "work", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(shared, "shared()", false, semanticRange(2)),
                        call(branch, "branch()", false, semanticRange(3)))
                .outgoing(shared)
                .outgoing(branch, call(shared, "sharedAgain()", false, semanticRange(22)))
                .failOutgoingOnRequest(
                        shared, 2, new IllegalStateException("SHARED_FAILURE_SENTINEL"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(shared), type(branch)), root, 2);

        assertThat(semantic.outgoingRequests()).containsExactly(root, shared, branch, shared);
        CallNode readableShared = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "AlphaShared".equals(node.methodId().className()))
                .filter(node -> CallType.INTERNAL_CLASS.equals(node.callType()))
                .findFirst()
                .orElseThrow();
        assertThat(readableShared.code()).isEqualTo("work source");
        CallEdge successfulEdge = result.graph().edges().stream()
                .filter(edge -> edge.caller().value().equals("node-1"))
                .filter(edge -> edge.callee().equals(readableShared.nodeId()))
                .findFirst()
                .orElseThrow();
        CallEdge failedEdge = result.graph().edges().stream()
                .filter(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                .findFirst()
                .orElseThrow();
        assertThat(successfulEdge.callee()).isNotEqualTo(failedEdge.callee());
        assertThat(result.graph().nodes()).filteredOn(node -> node.nodeId().equals(failedEdge.callee()))
                .singleElement().extracting(CallNode::callType).isEqualTo(CallType.UNRESOLVED);
        assertThat(result.toString()).doesNotContain("SHARED_FAILURE_SENTINEL");
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("SHARED_FAILURE_SENTINEL");
    }

    @Test
    void should_keep_shared_readable_node_when_later_recursive_occurrence_fails() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod shared = method("AlphaShared", "work", List.of(), 10);
        SemanticMethod branch = method("BetaBranch", "work", List.of(), 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(shared, "shared()", false, semanticRange(2)),
                        call(branch, "branch()", false, semanticRange(3)))
                .outgoing(shared)
                .outgoing(branch, call(shared, "sharedAgain()", false, semanticRange(22)))
                .failOutgoingOnRequest(
                        shared, 2, new IllegalStateException("RECURSIVE_SHARED_FAILURE_SENTINEL"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(shared), type(branch)), root, 3);

        assertThat(result.partial()).isTrue();
        assertThat(semantic.outgoingRequests()).containsExactly(root, shared, branch, shared);
        CallNode readableShared = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "AlphaShared".equals(node.methodId().className()))
                .filter(node -> CallType.INTERNAL_CLASS.equals(node.callType()))
                .findFirst()
                .orElseThrow();
        assertThat(readableShared.code()).isEqualTo("work source");
        CallEdge successfulEdge = result.graph().edges().stream()
                .filter(edge -> edge.caller().value().equals("node-1"))
                .filter(edge -> edge.callee().equals(readableShared.nodeId()))
                .findFirst()
                .orElseThrow();
        CallEdge failedEdge = result.graph().edges().stream()
                .filter(edge -> ResolutionStrategy.UNRESOLVED_TARGET.equals(edge.resolutionStrategy()))
                .findFirst()
                .orElseThrow();
        assertThat(successfulEdge.callee()).isNotEqualTo(failedEdge.callee());
        assertThat(result.graph().nodes()).filteredOn(node -> node.nodeId().equals(failedEdge.callee()))
                .singleElement().extracting(CallNode::callType).isEqualTo(CallType.UNRESOLVED);
        assertThat(result.toString()).doesNotContain("RECURSIVE_SHARED_FAILURE_SENTINEL");
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("RECURSIVE_SHARED_FAILURE_SENTINEL");
    }

    @Test
    void should_redirect_only_exact_same_line_occurrence_when_later_recursive_query_fails() {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod shared = method("Shared", "work", List.of(), 10);
        SemanticRange firstRange = semanticRange(2, 4, 2, 12);
        SemanticRange secondRange = semanticRange(2, 20, 2, 28);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(shared, "shared()", false, firstRange, secondRange))
                .outgoing(shared)
                .failOutgoingOnRequest(
                        shared, 2, new IllegalStateException("EXACT_EDGE_FAILURE_SENTINEL"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(shared)), root, 3);

        CallNode readableShared = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "Shared".equals(node.methodId().className()))
                .filter(node -> CallType.INTERNAL_CLASS.equals(node.callType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.graph().edges()).extracting(CallEdge::resolutionStrategy)
                .containsExactly(
                        ResolutionStrategy.JDT_CALL_HIERARCHY,
                        ResolutionStrategy.UNRESOLVED_TARGET);
        assertThat(result.graph().edges().getFirst().callee()).isEqualTo(readableShared.nodeId());
        assertThat(result.graph().edges().get(1).callee()).isNotEqualTo(readableShared.nodeId());
        assertThat(result.toString()).doesNotContain("EXACT_EDGE_FAILURE_SENTINEL");
    }

    @Test
    void should_remove_first_time_failed_target_and_publish_only_unresolved_occurrence() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod failing = method("Failing", "failedSourceSentinel", List.of(), 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root, call(failing, "failing()", false, semanticRange(2)))
                .failOutgoing(failing, new IllegalStateException("FIRST_FAILURE_EXCEPTION_SENTINEL"));

        CallGraphBuildResult result = builder(semantic).build(
                SNAPSHOT, syntax(type(root), type(failing)), root, 3);

        assertThat(result.partial()).isTrue();
        assertThat(result.graph().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET);
            assertThat(result.graph().nodes()).filteredOn(node -> node.nodeId().equals(edge.callee()))
                    .singleElement().extracting(CallNode::callType).isEqualTo(CallType.UNRESOLVED);
        });
        assertThat(result.graph().nodes()).hasSize(2)
                .noneMatch(node -> Objects.nonNull(node.methodId())
                        && "Failing".equals(node.methodId().className())
                        && CallType.INTERNAL_CLASS.equals(node.callType()));
        assertThat(result.graph().legacyFlattened().methods()).hasSize(2);
        assertThat(result.toString()).doesNotContain(
                "failedSourceSentinel source", "FIRST_FAILURE_EXCEPTION_SENTINEL");
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain(
                "failedSourceSentinel source", "FIRST_FAILURE_EXCEPTION_SENTINEL");
    }

    @Test
    void should_preserve_nested_subtree_when_interface_query_failure_isolated_to_its_occurrence() throws Exception {
        SemanticMethod root = method("Root", "run", List.of(), 0);
        SemanticMethod shared = method("AlphaShared", "work", List.of(), 10);
        SemanticMethod failing = method("BetaFailing", "work", List.of(), 20);
        SemanticMethod partial = method("DeltaPartial", "discardedSourceSentinel", List.of(), 30);
        SemanticMethod contract = method("OmegaContract", "work", List.of(), 40);
        SemanticMethod discardedSecret = method("AlphaDiscardedSecret", "read", List.of(), 50);
        SemanticMethod later = method("ZetaLater", "work", List.of(), 60);
        SemanticMethod publishedSecret = method("AlphaPublishedSecret", "read", List.of(), 70);
        SemanticRange reusedRange = semanticRange(42, 4, 42, 12);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(root,
                        call(shared, "shared()", false, semanticRange(2)),
                        call(failing, "failing()", false, semanticRange(3)),
                        call(later, "later()", false, semanticRange(4)))
                .outgoing(shared)
                .outgoing(failing,
                        call(partial, "partial()", false, semanticRange(22)),
                        call(contract, "contract()", false, semanticRange(23)))
                .outgoing(partial,
                        call(discardedSecret, "discardedSecret()", false, semanticRange(32)),
                        call(shared, "sharedFromDiscarded()", false, reusedRange))
                .failOutgoingOnRequest(
                        shared, 2, new IllegalArgumentException("DISCARDED_CUTOFF_ERROR_SENTINEL"))
                .failImplementations(
                        contract, new IllegalStateException("LATE_FAILURE_EXCEPTION_SENTINEL"))
                .outgoing(later,
                        call(publishedSecret, "publishedSecret()", false, semanticRange(62)),
                        call(shared, "sharedFromLater()", false, reusedRange),
                        call(root, "rootCycle()", false, semanticRange(63)));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return methodId.className().contains("Secret")
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };

        CallGraphBuildResult result = builder(semantic, policy).build(
                SNAPSHOT,
                syntax(type(root), type(shared), type(failing), type(partial),
                        type(contract, TypeKind.INTERFACE, List.of(), false),
                        type(discardedSecret), type(later), type(publishedSecret)),
                root,
                3);

        assertThat(semantic.outgoingRequests())
                .containsExactly(root, shared, failing, partial, shared, later, shared);
        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).extracting(AnalysisError::detail)
                .containsExactly("IllegalArgumentException", "IllegalStateException");
        assertThat(result.graph().nodes()).anySatisfy(node -> {
            assertThat(node.methodId()).isNotNull();
            assertThat(node.methodId().className()).isEqualTo("DeltaPartial");
        });
        assertThat(result.graph().nodes()).noneMatch(node -> Objects.nonNull(node.methodId())
                && "OmegaContract".equals(node.methodId().className())
                && !CallType.UNRESOLVED.equals(node.callType()));
        CallNode failingNode = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "BetaFailing".equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
        CallEdge failedContractEdge = result.graph().edges().stream()
                .filter(edge -> edge.caller().equals(failingNode.nodeId()))
                .filter(edge -> "contract()".equals(edge.callExpression()))
                .findFirst()
                .orElseThrow();
        assertThat(failedContractEdge.resolutionStrategy()).isEqualTo(ResolutionStrategy.UNRESOLVED_TARGET);
        assertThat(result.graph().nodes())
                .filteredOn(node -> node.nodeId().equals(failedContractEdge.callee()))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.callType()).isEqualTo(CallType.UNRESOLVED);
                    assertThat(node.code()).isEmpty();
                });
        CallNode sharedNode = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "AlphaShared".equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
        CallNode laterNode = result.graph().nodes().stream()
                .filter(node -> Objects.nonNull(node.methodId()))
                .filter(node -> "ZetaLater".equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
        assertThat(result.graph().edges())
                .filteredOn(edge -> edge.caller().equals(laterNode.nodeId())
                        && edge.callee().equals(sharedNode.nodeId()))
                .singleElement()
                .extracting(CallEdge::resolutionStrategy)
                .isEqualTo(ResolutionStrategy.JDT_CALL_HIERARCHY);
        assertThat(result.graph().nodes())
                .filteredOn(node -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility()))
                .extracting(node -> node.nodeId().value())
                .containsExactly("restricted-node-1", "restricted-node-2");
        assertThat(result.graph().nodes())
                .filteredOn(node -> CallType.CYCLE_BACK_EDGE.equals(node.callType()))
                .isEmpty();
        assertThat(result.toString()).doesNotContain(
                "DISCARDED_CUTOFF_ERROR_SENTINEL",
                "LATE_FAILURE_EXCEPTION_SENTINEL");
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain(
                "DISCARDED_CUTOFF_ERROR_SENTINEL",
                "LATE_FAILURE_EXCEPTION_SENTINEL");
    }


    private SemanticCallGraphBuilder builder(JavaSemanticService semanticService) {
        ReadPolicy readable = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return EvidenceVisibility.READABLE;
            }
        };
        return builder(semanticService, readable);
    }

    private SemanticCallGraphBuilder builder(JavaSemanticService semanticService, ReadPolicy readPolicy) {
        return new SemanticCallGraphBuilder(
                semanticService, new CallGraphClassifier(), new SpringImplementationSelector(), readPolicy);
    }

    private static RepositorySyntax syntax(ClassMetadata... classes) {
        return new RepositorySyntax(List.of(), List.of(classes));
    }

    private static ClassMetadata type(SemanticMethod method, SyntaxInvocation... invocations) {
        return type(method.className(), TypeKind.CLASS, List.of(), false, List.of(), List.of(),
                syntaxMethod(method, List.of(invocations), ""));
    }

    private static ClassMetadata type(
            SemanticMethod method, TypeKind kind, List<String> annotations, boolean primary) {
        return type(method.className(), kind, annotations, primary, List.of(), List.of(),
                syntaxMethod(method, List.of(), ""));
    }

    private static ClassMetadata type(
            String className,
            TypeKind kind,
            List<String> annotations,
            boolean primary,
            List<String> qualifiers,
            List<String> profiles,
            MethodSignature... methods) {
        SyntaxRange range = syntaxRange(0, 0, 50, 0);
        return new ClassMetadata(
                className, "com.example", "com.example." + className, className + ".java", kind, false,
                List.of(), List.of(), annotations, List.of(), List.of(), List.of(methods), false, false, profiles,
                range, new SourceSlice(range, "class " + className + " {}"), primary, qualifiers);
    }

    private static ClassMetadata typeInPackage(String packageName, String className) {
        SyntaxRange range = syntaxRange(0, 0, 50, 0);
        return new ClassMetadata(
                className, packageName, packageName + "." + className, className + ".java", TypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, false, List.of(),
                range, new SourceSlice(range, "class " + className + " {}"), false, List.of());
    }

    private static MethodSignature syntaxMethod(
            SemanticMethod method, List<SyntaxInvocation> invocations, String sql) {
        return syntaxMethod(method, invocations, sql, List.of(), Optional.empty());
    }

    private static MethodSignature syntaxMethod(
            SemanticMethod method,
            List<SyntaxInvocation> invocations,
            String sql,
            List<TypeReference> parameterTypes,
            Optional<TypeReference> returnType) {
        return syntaxMethod(method, invocations, sql, parameterTypes, returnType, List.of());
    }

    private static MethodSignature syntaxMethod(
            SemanticMethod method,
            List<SyntaxInvocation> invocations,
            String sql,
            List<TypeReference> parameterTypes,
            Optional<TypeReference> returnType,
            List<String> annotations) {
        SemanticRange semanticRange = method.location().range();
        SyntaxRange range = syntaxRange(
                semanticRange.start().line(), semanticRange.start().character(),
                semanticRange.end().line(), semanticRange.end().character());
        String sqlValue = sql.isBlank() ? null : sql;
        ClassMetadata.SqlSource sqlSource = sql.isBlank() ? null : ClassMetadata.SqlSource.ANNOTATION;
        return new MethodSignature(
                method.methodName(), method.parameterTypes(), annotations, sqlValue, sqlSource,
                range.start().line() + 1, range.end().line() + 1, range,
                new SourceSlice(range, method.methodName() + " source"), parameterTypes, returnType, invocations);
    }

    private static SemanticMethod method(String className, String methodName, List<String> parameters, int line) {
        return methodWithReturn(className, methodName, parameters, "void", line);
    }

    private static SemanticMethod methodWithReturn(
            String className,
            String methodName,
            List<String> parameters,
            String returnType,
            int line) {
        SemanticRange range = semanticRange(line, 0, line + 5, 0);
        return new SemanticMethod(
                "com.example", className, methodName, parameters, returnType,
                new SemanticLocation("file:///fixture/" + className + ".java", range, range));
    }

    private static SemanticMethod methodAtPath(String className, String methodName, Path path, int line) {
        SemanticRange range = semanticRange(line, 0, line + 5, 0);
        return new SemanticMethod(
                "com.example", className, methodName, List.of(), "void",
                new SemanticLocation(path.toUri().toString(), range, range));
    }

    private static SemanticCall call(
            SemanticMethod target, String signature, boolean external, SemanticRange... callSites) {
        return new SemanticCall(
                Optional.of(target), signature, List.of(callSites), external, SemanticResolutionOrigin.CALL_HIERARCHY);
    }

    private static SemanticCall unresolvedCall(String signature, boolean external, SemanticRange... callSites) {
        return new SemanticCall(
                Optional.empty(), signature, List.of(callSites), external, SemanticResolutionOrigin.CALL_HIERARCHY);
    }

    private static SyntaxInvocation invocation(
            String expression, int line, String qualifier, int anchorCharacter) {
        return new SyntaxInvocation(
                InvocationKind.METHOD,
                syntaxRange(line, 0, line, expression.length()),
                expression,
                "port",
                "",
                qualifier,
                Optional.empty(),
                new SyntaxPosition(line, anchorCharacter));
    }

    private static SyntaxInvocation invocation(
            String expression,
            SyntaxRange range,
            InvocationTarget resolvedTarget,
            SyntaxPosition resolutionAnchor) {
        return new SyntaxInvocation(
                InvocationKind.METHOD,
                range,
                expression,
                "port",
                "",
                "",
                Optional.ofNullable(resolvedTarget),
                resolutionAnchor);
    }

    private static SemanticRange semanticRange(int line) {
        return semanticRange(line, 0, line, 5);
    }

    private static SemanticRange semanticRange(SyntaxRange range) {
        return semanticRange(
                range.start().line(), range.start().character(), range.end().line(), range.end().character());
    }

    private static SemanticRange semanticRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SemanticRange(
                new SemanticPosition(startLine, startCharacter), new SemanticPosition(endLine, endCharacter));
    }

    private static SyntaxRange syntaxRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
    }

    private static final class FakeSemanticService implements JavaSemanticService {

        private final Map<SemanticMethod, List<SemanticCall>> outgoing = new HashMap<>();
        private final Map<SemanticMethod, RuntimeException> outgoingFailures = new HashMap<>();
        private final Map<SemanticMethod, RuntimeException> implementationFailures = new HashMap<>();
        private final Map<SemanticMethod, RequestFailure> requestFailures = new HashMap<>();
        private final Map<SemanticMethod, Integer> outgoingRequestCounts = new HashMap<>();
        private final Map<DefinitionKey, SemanticCall> definitions = new HashMap<>();
        private final Map<DefinitionKey, RuntimeException> definitionFailures = new HashMap<>();
        private final Map<SemanticMethod, List<SemanticMethod>> implementations = new HashMap<>();
        private final List<SemanticMethod> outgoingRequests = new ArrayList<>();
        private final List<SemanticRange> definitionRequests = new ArrayList<>();
        private final List<SemanticCallSite> definitionCallSites = new ArrayList<>();
        private final List<SemanticMethod> implementationRequests = new ArrayList<>();

        FakeSemanticService outgoing(SemanticMethod method, SemanticCall... calls) {
            outgoing.put(method, List.of(calls));
            return this;
        }

        FakeSemanticService failOutgoing(SemanticMethod method, RuntimeException exception) {
            outgoingFailures.put(method, exception);
            return this;
        }

        FakeSemanticService failOutgoingOnRequest(
                SemanticMethod method, int requestNumber, RuntimeException exception) {
            requestFailures.put(method, new RequestFailure(requestNumber, exception));
            return this;
        }

        FakeSemanticService definition(SemanticMethod caller, SemanticRange range, SemanticCall call) {
            definitions.put(new DefinitionKey(caller, range), call);
            return this;
        }

        FakeSemanticService failDefinition(
                SemanticMethod caller, SemanticRange range, RuntimeException exception) {
            definitionFailures.put(new DefinitionKey(caller, range), exception);
            return this;
        }

        FakeSemanticService implementations(SemanticMethod method, SemanticMethod... candidates) {
            implementations.put(method, List.of(candidates));
            return this;
        }

        FakeSemanticService failImplementations(SemanticMethod method, RuntimeException exception) {
            implementationFailures.put(method, exception);
            return this;
        }

        List<SemanticMethod> outgoingRequests() {
            return List.copyOf(outgoingRequests);
        }

        List<SemanticRange> definitionRequests() {
            return List.copyOf(definitionRequests);
        }

        List<SemanticCallSite> definitionCallSites() {
            return List.copyOf(definitionCallSites);
        }

        List<SemanticMethod> implementationRequests() {
            return List.copyOf(implementationRequests);
        }

        @Override
        public SemanticMethod resolveMethod(
                RepositorySnapshot snapshot, String packageName, String className, String methodSignature) {
            throw new UnsupportedOperationException("not used by traversal");
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
            outgoingRequests.add(method);
            int requestCount = outgoingRequestCounts.merge(method, 1, Integer::sum);
            Optional<RequestFailure> requestFailure = Optional.ofNullable(requestFailures.get(method));
            if (requestFailure.isPresent() && requestFailure.orElseThrow().requestNumber() == requestCount) {
                throw requestFailure.orElseThrow().exception();
            }
            Optional<RuntimeException> failure = Optional.ofNullable(outgoingFailures.get(method));
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return outgoing.getOrDefault(method, List.of());
        }

        @Override
        public Optional<SemanticCall> resolveCallAt(
                RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
            definitionCallSites.add(callSite);
            SemanticRange range = callSite.range();
            definitionRequests.add(range);
            DefinitionKey key = new DefinitionKey(caller, range);
            Optional<RuntimeException> failure = Optional.ofNullable(definitionFailures.get(key));
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return Optional.ofNullable(definitions.get(key));
        }

        @Override
        public List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            implementationRequests.add(method);
            Optional<RuntimeException> failure = Optional.ofNullable(implementationFailures.get(method));
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return implementations.getOrDefault(method, List.of());
        }
    }

    private record DefinitionKey(SemanticMethod caller, SemanticRange range) {
    }

    private record RequestFailure(int requestNumber, RuntimeException exception) {
    }
}
