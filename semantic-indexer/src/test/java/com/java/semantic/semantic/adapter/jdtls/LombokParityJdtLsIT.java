package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.RepositorySyntax;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真實 JDT LS 下證明 Lombok 生成成員與手寫成員在 outgoing/incoming 呼叫圖上的行為差異
 * <p>
 * outgoing：生成成員呼叫改標為 {@code LOMBOK_GENERATED} 不透明邊，手寫成員維持一般 LOCAL 邊
 * <p>
 * incoming：以生成成員為根的追溯在解析宣告階段即失敗封閉，永遠無法建出片段
 */
@Tag("jdtls-it")
class LombokParityJdtLsIT {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/lombok-generated");
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("lombok-generated");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("c".repeat(40));
    private static final String PACKAGE = "com.example.lombokgen";
    private static final int OUTGOING_DEPTH = 1;
    private static final int DEPTH_TWO_NODE_BUDGET = 40;

    @TempDir
    Path workingTree;

    @TempDir
    Path workspaceData;

    @Test
    void should_relabel_lombok_generated_call_sites_as_opaque_edges_and_resolve_hand_written_members_normally()
            throws IOException {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        Path root = copyFixture();
        DefaultJdtWorkspaceManager manager = manager(properties(home));
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        SemanticCallGraphBuilder builder = new SemanticCallGraphBuilder(service, new SpringImplementationSelector());

        try {
            RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(root);

            assertGetterCallSiteIsLombokGenerated(builder, service, snapshot, syntax);
            assertSetterCallSiteIsLombokGenerated(builder, service, snapshot, syntax);
            assertHandWrittenMethodResolvesNormally(builder, service, snapshot, syntax);
            assertValueGetterCallSiteIsLombokGenerated(builder, service, snapshot, syntax);
            assertBuilderEntryIsLombokGeneratedAndChainStaysUnresolved(builder, service, snapshot, syntax);
            assertNoArgsConstructorCallSiteIsLombokGenerated(builder, service, snapshot, syntax);
            assertAllArgsConstructorCallSiteIsLombokGenerated(builder, service, snapshot, syntax);
            assertIncomingRootAtGeneratedMemberFailsClosed(syntax);
        } finally {
            manager.shutdownAll();
        }
    }

    private void assertGetterCallSiteIsLombokGenerated(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "readTotal", List.of("Order"));
        assertSingleLombokGeneratedEdge(fragment, PACKAGE + ".Order#getTotal()");
    }

    private void assertSetterCallSiteIsLombokGenerated(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "writeTotal",
                List.of("Order", "double"));
        assertSingleLombokGeneratedEdge(fragment, PACKAGE + ".Order#setTotal(..)");
    }

    private void assertHandWrittenMethodResolvesNormally(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "readManualNote", List.of("Order"));

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.warnings()).isEmpty();
        assertThat(fragment.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isIn(
                    ResolutionStrategy.JDT_CALL_HIERARCHY, ResolutionStrategy.JDT_DEFINITION_FALLBACK);
            GraphNode calleeNode = nodeById(fragment, edge.calleeNodeId());
            assertThat(calleeNode.contentState()).isEqualTo(NodeContentState.FULL_SOURCE);
            assertThat(calleeNode.target()).isPresent();
            assertThat(calleeNode.target().orElseThrow().methodName()).isEqualTo("getManualNote");
        });
    }

    private void assertValueGetterCallSiteIsLombokGenerated(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "readCustomerName",
                List.of("Customer"));
        assertSingleLombokGeneratedEdge(fragment, PACKAGE + ".Customer#getName()");
    }

    /**
     * builder() 進入點與內嵌 chain 的觀察行為不同，拆成兩段各自斷言
     * <p>
     * builder() 進入點：接收者型別是 Shipment 本身，不依賴任何繫結即可判定，維持 LOMBOK_GENERATED 不透明邊
     * <p>
     * 內嵌 chain（{@code .carrier(x).build()}）：本環境的 JDTLS_HOME 未安裝 Lombok agent（spec 明列為
     * out-of-scope），ECJ 因此無法為 {@code Shipment.builder()} 的回傳值繫結型別，語法層也拿不到
     * ShipmentBuilder 的接收者宣告；以純文字重建接收者型別不滿足 spec 的「語法證據完整」門檻，因此依設計維持
     * 失敗封閉，不得被 GeneratedMemberEvidence 重新標記——此為 adjudicated 的 spec-correct 行為，而非缺陷
     */
    private void assertBuilderEntryIsLombokGeneratedAndChainStaysUnresolved(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "buildShipment", List.of("String"));

        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionStrategy()).isEqualTo(ResolutionStrategy.LOMBOK_GENERATED);
            GraphNode calleeNode = nodeById(fragment, edge.calleeNodeId());
            assertThat(calleeNode.externalSymbol()).isEqualTo(PACKAGE + ".Shipment#builder()");
            assertThat(calleeNode.contentState()).isEqualTo(NodeContentState.EXTERNAL);
            assertThat(calleeNode.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
        });
        assertThat(fragment.warnings())
                .filteredOn(warning -> "DESCENDANT_CALL_UNRESOLVED".equals(warning.code()))
                .extracting(warning -> warning.callExpression().orElseThrow())
                .containsExactlyInAnyOrder(
                        "Shipment.builder().carrier(carrier)",
                        "Shipment.builder().carrier(carrier).build()");
    }

    private void assertNoArgsConstructorCallSiteIsLombokGenerated(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "createBlankInvoice", List.of());
        assertSingleLombokGeneratedEdge(fragment, PACKAGE + ".Invoice#<init>(0)");
    }

    private void assertAllArgsConstructorCallSiteIsLombokGenerated(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        OutgoingGraphFragment fragment = outgoingFragment(
                builder, service, snapshot, syntax, "OrderFlow", "createFullInvoice",
                List.of("String", "double"));
        assertSingleLombokGeneratedEdge(fragment, PACKAGE + ".Invoice#<init>(2)");
    }

    /**
     * incoming 追溯以生成成員為根時，必須在解析宣告階段即失敗封閉，永遠不會走到片段建構
     * <p>
     * {@code SemanticAnalysisApplicationService} 對 outgoing/incoming 兩個方向共用同一段
     * 根解析程式碼，此處直接重現該段落，證明生成成員無法被重新扎根（re-root）
     */
    private void assertIncomingRootAtGeneratedMemberFailsClosed(RepositorySyntax syntax) {
        MethodTarget generatedRoot = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(PACKAGE, "Order"),
                        "src/main/java/com/example/lombokgen/Order.java"),
                "getTotal",
                List.of());

        assertThat(new CanonicalMethodDeclarationResolver().resolve(syntax, generatedRoot).status())
                .isEqualTo(AnalysisTargetStatus.UNRESOLVED);
    }

    private void assertSingleLombokGeneratedEdge(OutgoingGraphFragment fragment, String externalSymbol) {
        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.SUCCESS);
        assertThat(fragment.warnings()).isEmpty();
        assertThat(fragment.edges()).singleElement()
                .extracting(GraphEdge::resolutionStrategy)
                .isEqualTo(ResolutionStrategy.LOMBOK_GENERATED);
        assertLombokGeneratedCalleeExists(fragment, externalSymbol);
    }

    private void assertLombokGeneratedCalleeExists(OutgoingGraphFragment fragment, String externalSymbol) {
        GraphEdge edge = fragment.edges().stream()
                .filter(candidate -> ResolutionStrategy.LOMBOK_GENERATED.equals(candidate.resolutionStrategy()))
                .filter(candidate -> externalSymbol.equals(nodeById(fragment, candidate.calleeNodeId()).externalSymbol()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing LOMBOK_GENERATED edge for " + externalSymbol));
        GraphNode calleeNode = nodeById(fragment, edge.calleeNodeId());
        assertThat(calleeNode.contentState()).isEqualTo(NodeContentState.EXTERNAL);
        assertThat(calleeNode.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
        assertThat(fragment.warnings()).noneMatch(warning -> "DESCENDANT_CALL_UNRESOLVED".equals(warning.code())
                && warning.nodeId().equals(fragment.rootNodeId()));
    }

    private GraphNode nodeById(OutgoingGraphFragment fragment, CallNodeId nodeId) {
        return fragment.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node " + nodeId));
    }

    private OutgoingGraphFragment outgoingFragment(
            SemanticCallGraphBuilder builder,
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            String className,
            String methodName,
            List<String> parameterTypes) {
        MethodTarget target = methodTarget(syntax, className, methodName, parameterTypes);
        SemanticDeclarationAnchor anchor = anchor(syntax, target);
        SemanticMethod root = service.resolveExactMethod(snapshot, anchor);
        return builder.build(snapshot, syntax, target, root, OUTGOING_DEPTH, DEPTH_TWO_NODE_BUDGET);
    }

    private SemanticDeclarationAnchor anchor(RepositorySyntax syntax, MethodTarget target) {
        MethodTarget resolved = new CanonicalMethodDeclarationResolver().resolve(syntax, target)
                .target()
                .orElseThrow(() -> new AssertionError("missing canonical method declaration"));
        return syntax.sourceTypes().stream()
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> method.analysisTarget().target().filter(resolved::equals).isPresent())
                .map(method -> new SemanticDeclarationAnchor(
                        resolved,
                        new SemanticPosition(method.namePosition().line(), method.namePosition().character())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing canonical method declaration position"));
    }

    private MethodTarget methodTarget(
            RepositorySyntax syntax, String className, String methodName, List<String> parameterTypes) {
        return syntax.sourceTypes().stream()
                .filter(metadata -> PACKAGE.equals(metadata.declaration().identity().javaType().packageName()))
                .filter(metadata -> className.equals(metadata.declaration().identity().javaType().className()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .filter(method -> parameterTypes.equals(method.paramTypes()))
                .flatMap(method -> method.analysisTarget().target().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing exact syntax target " + className + "#" + methodName));
    }

    private JdtLsProperties properties(Path home) {
        return new JdtLsProperties(
                true,
                home,
                workspaceData,
                Duration.ofSeconds(180),
                Duration.ofSeconds(600),
                Duration.ofSeconds(60),
                1,
                Duration.ofMinutes(30),
                Duration.ofMinutes(1),
                "2g");
    }

    private DefaultJdtWorkspaceManager manager(JdtLsProperties properties) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics lifecycleMetrics = new JdtWorkspaceLifecycleMetrics(meterRegistry);
        return new DefaultJdtWorkspaceManager(
                new JdtLsProcessFactory(properties),
                new JdtLsReadinessProbe(properties),
                properties,
                meterRegistry,
                lifecycleMetrics);
    }

    private Path copyFixture() throws IOException {
        Path source = FIXTURE.toAbsolutePath();
        Path target = workingTree.resolve(REPOSITORY_ID.value());
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }
}
