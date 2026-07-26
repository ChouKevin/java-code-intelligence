package com.java.semantic.api;

import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AnalysisResponseMapper} 之單元測試,聚焦解析策略對外部分類的映射 */
class AnalysisResponseMapperTest {

    private static final MethodTarget TARGET = new MethodTarget(
            "src/main/java/com/acme/OrderService.java", "com.acme", "OrderService", "place", List.of());

    private final AnalysisResponseMapper mapper = new AnalysisResponseMapper();

    @Test
    void should_map_resolution_strategy_to_its_resolution_category() {
        CallNodeId root = new CallNodeId("node-root");
        CallNodeId lombokCaller = new CallNodeId("node-lombok-caller");
        CallNodeId jdtCaller = new CallNodeId("node-jdt-caller");
        CallSiteRange callSite = new CallSiteRange(TARGET.sourceFile(), 12, 3, 12, 15);

        OutgoingGraphFragment fragment = new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                RepositoryRevision.fixture(),
                root,
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                List.of(new GraphNode(
                        root,
                        Optional.of(TARGET),
                        "",
                        NodeContentState.TARGET_ONLY,
                        NodeTraversalState.BUDGET_CUTOFF,
                        DispatchKind.SYNCHRONOUS,
                        Optional.empty(),
                        Optional.empty())),
                List.of(
                        new GraphEdge(
                                lombokCaller,
                                root,
                                callSite,
                                "place(orderId)",
                                ResolutionStrategy.LOMBOK_GENERATED,
                                List.of("lombok-generated-accessor")),
                        new GraphEdge(
                                jdtCaller,
                                root,
                                callSite,
                                "place(orderId)",
                                ResolutionStrategy.JDT_CALL_HIERARCHY,
                                List.of("jdt-call-hierarchy"))),
                List.of(),
                List.of());

        OutgoingCallGraphResponse response = mapper.toResponse(fragment);

        assertThat(response.edges()).hasSize(2);
        assertThat(response.edges().get(0).resolutionStrategy()).isEqualTo("LOMBOK_GENERATED");
        assertThat(response.edges().get(0).category()).isEqualTo("RESOLVED_OPAQUE");
        assertThat(response.edges().get(1).resolutionStrategy()).isEqualTo("JDT_CALL_HIERARCHY");
        assertThat(response.edges().get(1).category()).isEqualTo("RESOLVED_ANALYZABLE");
        assertThat(response.nodes().get(0).dispatchKind()).isEqualTo("SYNCHRONOUS");
    }
}
