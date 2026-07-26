package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.api.AnalysisResponseMapper;
import com.java.semantic.api.dto.GraphEdgeResponse;
import com.java.semantic.callgraph.application.DirectCallRelationshipResolver;
import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.application.ExactMethodDeclarationResolver;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 在真實 JDT LS 下證明資料存取進入點在 outgoing/incoming 呼叫圖上的分類與方向對稱行為
 * <p>
 * outgoing：XML 支撐的 mapper 呼叫改標 {@code MYBATIS_MAPPER} 並帶 SQL 證據，API 層歸類
 * RESOLVED_OPAQUE；Spring Data repository 呼叫改標 {@code SPRING_DATA_REPOSITORY}，API 層
 * 同樣歸類 RESOLVED_OPAQUE；僅有 {@code @Mapper} 而無 SQL 或已知父介面的 DAO 呼叫改標
 * {@code DATA_ACCESS_WITHOUT_EVIDENCE} 並保留未解析警告
 * <p>
 * incoming：以 mapper 介面方法為根，前向驗證依資料存取證據接受呼叫者，於片段層回傳
 * {@code MYBATIS_MAPPER} 呼叫者邊，方向對稱維持
 */
@Tag("jdtls-it")
class DataAccessParityJdtLsIT {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/multi-module-data-access");
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("multi-module-data-access");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("d".repeat(40));
    private static final String SERVICE_PACKAGE = "com.example.service";
    private static final String PERSISTENCE_PACKAGE = "com.example.persistence";
    private static final String ORDER_XML_SQL = "SELECT * FROM orders WHERE customer_id = #{customerId}";
    private static final int OUTGOING_DEPTH = 1;
    private static final int DEPTH_TWO_NODE_BUDGET = 40;

    @TempDir
    Path workingTree;

    @TempDir
    Path workspaceData;

    @Test
    void should_classify_data_access_entry_points_and_preserve_incoming_direction() throws IOException {
        Path home = requireJdtlsHome(System.getenv("JDTLS_HOME"));
        Path root = copyFixture();
        DefaultJdtWorkspaceManager manager = manager(properties(home));
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        SemanticCallGraphBuilder builder = new SemanticCallGraphBuilder(service, new SpringImplementationSelector());

        try {
            RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(root);
            OutgoingGraphFragment fragment = outgoingFragment(
                    builder, service, snapshot, syntax,
                    SERVICE_PACKAGE, "OrderApplicationService", "reconcile", List.of("Long"));

            assertMapperEdgeCarriesXmlSqlAndMapsToResolvedOpaque(fragment);
            assertSpringDataRepositoryEdgeIsResolvedOpaque(fragment);
            assertAnnotatedDaoWithoutEvidenceKeepsWarning(fragment);
            assertIncomingRootAtMapperReturnsCallerEdge(service, snapshot, syntax);
        } finally {
            manager.shutdownAll();
        }
    }

    /**
     * XML 支撐的 mapper 呼叫改標 {@code MYBATIS_MAPPER}，帶 OrderMapper.xml 的 SQL 逐字證據，
     * 終端節點為 TARGET_ONLY/OPAQUE 並保留介面 MethodTarget；同一條邊經 API 回應對應層後歸類
     * {@code RESOLVED_OPAQUE}，於真實 JDT 下端到端證明 opaque 資料存取邊的分類
     */
    private void assertMapperEdgeCarriesXmlSqlAndMapsToResolvedOpaque(OutgoingGraphFragment fragment) {
        assertThat(fragment.edges())
                .filteredOn(edge -> ResolutionStrategy.MYBATIS_MAPPER.equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.evidence())
                            .anySatisfy(entry -> assertThat(entry).contains(ORDER_XML_SQL));
                    GraphNode callee = nodeById(fragment, edge.calleeNodeId());
                    assertThat(callee.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(callee.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
                    MethodTarget mapperTarget = callee.target().orElseThrow();
                    assertThat(mapperTarget.packageName()).isEqualTo(PERSISTENCE_PACKAGE);
                    assertThat(mapperTarget.className()).isEqualTo("OrderMapper");
                    assertThat(mapperTarget.methodName()).isEqualTo("xmlOnly");
                });

        List<GraphEdgeResponse> mappedEdges = new AnalysisResponseMapper().toResponse(fragment).edges();
        assertThat(mappedEdges)
                .filteredOn(edge -> "MYBATIS_MAPPER".equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.category()).isEqualTo("RESOLVED_OPAQUE");
                    assertThat(edge.evidence())
                            .anySatisfy(entry -> assertThat(entry).contains(ORDER_XML_SQL));
                });
    }

    /**
     * Spring Data repository 呼叫改標 {@code SPRING_DATA_REPOSITORY} 不透明邊，終端節點為
     * TARGET_ONLY/OPAQUE 並保留介面 MethodTarget；經 API 回應對應層後歸類 {@code RESOLVED_OPAQUE}
     * <p>
     * {@code interface CustomerRepository extends JpaRepository<..>} 的父介面被
     * {@code ClassMetadataExtractor} 放進 implementedTypes，證據規則取 extendedTypes 與
     * implementedTypes 聯集後即能命中已知父介面
     */
    private void assertSpringDataRepositoryEdgeIsResolvedOpaque(OutgoingGraphFragment fragment) {
        assertThat(fragment.edges())
                .filteredOn(edge -> ResolutionStrategy.SPRING_DATA_REPOSITORY.equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> {
                    GraphNode callee = nodeById(fragment, edge.calleeNodeId());
                    assertThat(callee.contentState()).isEqualTo(NodeContentState.TARGET_ONLY);
                    assertThat(callee.traversalState()).isEqualTo(NodeTraversalState.OPAQUE);
                    assertThat(callee.target().orElseThrow().className()).isEqualTo("CustomerRepository");
                });

        List<GraphEdgeResponse> mappedEdges = new AnalysisResponseMapper().toResponse(fragment).edges();
        assertThat(mappedEdges)
                .filteredOn(edge -> "SPRING_DATA_REPOSITORY".equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> assertThat(edge.category()).isEqualTo("RESOLVED_OPAQUE"));
    }

    private void assertAnnotatedDaoWithoutEvidenceKeepsWarning(OutgoingGraphFragment fragment) {
        assertThat(fragment.status()).isEqualTo(GraphAnalysisStatus.PARTIAL);
        assertThat(fragment.edges())
                .filteredOn(edge -> ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE.equals(edge.resolutionStrategy()))
                .singleElement()
                .satisfies(edge -> {
                    GraphNode callee = nodeById(fragment, edge.calleeNodeId());
                    assertThat(callee.target().orElseThrow().className()).isEqualTo("AuditDao");
                });
        assertThat(fragment.warnings())
                .filteredOn(warning -> "DESCENDANT_CALL_UNRESOLVED".equals(warning.code()))
                .extracting(warning -> warning.callExpression().orElseThrow())
                .anySatisfy(expression -> assertThat(expression).contains("auditDao.latest()"));
    }

    /**
     * 以 mapper 介面方法為根的 incoming 追溯，經前向驗證的資料存取證據接受路徑後，於片段層回傳
     * 指向該 mapper 的呼叫者邊（{@code MYBATIS_MAPPER}），證明方向對稱在建構器層維持
     */
    private void assertIncomingRootAtMapperReturnsCallerEdge(
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        MethodTarget mapperTarget = methodTarget(
                syntax, PERSISTENCE_PACKAGE, "OrderMapper", "xmlOnly", List.of("Long"));
        SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, mapperTarget);
        SemanticMethod mapperRoot = service.resolveExactMethod(snapshot, anchor);
        IncomingSemanticCallGraphBuilder incomingBuilder = new IncomingSemanticCallGraphBuilder(
                service, new DirectCallRelationshipResolver(service, new SpringImplementationSelector()));

        IncomingGraphFragment fragment = incomingBuilder.build(
                snapshot, syntax, mapperTarget, mapperRoot, 1, DEPTH_TWO_NODE_BUDGET);

        assertThat(fragment.edges())
                .filteredOn(edge -> ResolutionStrategy.MYBATIS_MAPPER.equals(edge.resolutionStrategy()))
                .anySatisfy(edge -> {
                    GraphNode caller = nodeById(fragment, edge.callerNodeId());
                    assertThat(caller.target().orElseThrow().className()).isEqualTo("OrderApplicationService");
                    assertThat(caller.target().orElseThrow().methodName()).isEqualTo("reconcile");
                });
    }

    private GraphNode nodeById(IncomingGraphFragment fragment, CallNodeId nodeId) {
        return fragment.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node " + nodeId));
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
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
        MethodTarget target = methodTarget(syntax, packageName, className, methodName, parameterTypes);
        SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, target);
        SemanticMethod root = service.resolveExactMethod(snapshot, anchor);
        return builder.build(snapshot, syntax, target, root, OUTGOING_DEPTH, DEPTH_TWO_NODE_BUDGET);
    }

    private MethodTarget methodTarget(
            RepositorySyntax syntax,
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
        return syntax.classes().stream()
                .filter(metadata -> packageName.equals(metadata.packageName()))
                .filter(metadata -> className.equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .filter(method -> parameterTypes.equals(method.paramTypes()))
                .flatMap(method -> method.analysisTarget().target().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing exact syntax target " + className + "#" + methodName));
    }

    private Path requireJdtlsHome(String configuredHome) {
        assumeTrue(StringUtils.hasText(configuredHome),
                "JDTLS_HOME must be configured for real JDT LS integration tests");
        Path home = Path.of(configuredHome);
        assertThat(Files.isDirectory(home))
                .as("JDTLS_HOME must point at an installed JDT LS directory: %s", home)
                .isTrue();
        return home;
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
