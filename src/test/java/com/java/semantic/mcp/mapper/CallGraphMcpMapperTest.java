package com.java.semantic.mcp.mapper;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads;
import com.java.semantic.mcp.dto.source.McpEvidenceIdentityPayload;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** {@link CallGraphMcpMapper} 的 MCP adapter edge 合約測試 */
class CallGraphMcpMapperTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");

    private static final RepositoryRevision REVISION = RepositoryRevision.fixture();

    private static final MethodTarget TARGET = new MethodTarget(
            new SourceTypeIdentity(new JavaTypeIdentity("com.example", "OrderMapper"),
                    "src/main/java/com/example/OrderMapper.java"),
            "findOrder",
            List.of("String"));

    private static final MethodTarget DECLARATION_TARGET = new MethodTarget(
            new SourceTypeIdentity(new JavaTypeIdentity("com.example", "OrderPort"),
                    "src/main/java/com/example/OrderPort.java"),
            "findOrder",
            List.of("String"));

    private final CallGraphMcpMapper mapper = new CallGraphMcpMapper();

    @Test
    void should_project_mapper_follow_up_and_non_mapper_edge_through_both_graph_directions() {
        MapperStatementIdentity identity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrder"),
                "mapper/OrderMapper.xml",
                Optional.of("postgres"),
                2,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        List<GraphEdge> edges = List.of(
                new GraphEdge(
                        new CallNodeId("caller"), new CallNodeId("mapper"), new CallSiteRange(TARGET.sourceFile(), 4, 2, 4, 12),
                        "findOrder(id)", ResolutionStrategy.MYBATIS_MAPPER, List.of("mapper evidence"), List.of(identity),
                        Optional.of(DECLARATION_TARGET)),
                new GraphEdge(
                        new CallNodeId("caller"), new CallNodeId("local"), new CallSiteRange(TARGET.sourceFile(), 5, 2, 5, 12),
                        "localCall()", ResolutionStrategy.JDT_CALL_HIERARCHY, List.of("jdt evidence"), List.of()));
        OutgoingGraphFragment outgoing = outgoing(edges);
        IncomingGraphFragment incoming = incoming(edges);

        CallGraphMcpDtos.GraphResult outgoingResult = mapper.outgoing(REPOSITORY_ID, outgoing).result();
        CallGraphMcpDtos.GraphResult incomingResult = mapper.incoming(REPOSITORY_ID, incoming).result();

        assertEdges(outgoingResult, identity);
        assertEdges(incomingResult, identity);
        assertThat(CallGraphMcpDtos.GraphEdgeOutput.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("callerNodeId", "calleeNodeId", "callSite", "callExpression", "resolutionStrategy", "evidence", "availableFollowUps")
                .doesNotContain("evidenceSourceIdentities");
    }

    private static void assertEdges(CallGraphMcpDtos.GraphResult result, MapperStatementIdentity identity) {
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
        assertThat(result.edges()).hasSize(2);
        CallGraphMcpDtos.GraphEdgeOutput mapperEdge = result.edges().getFirst();
        assertThat(mapperEdge.callerNodeId()).isEqualTo(new CallNodeId("caller"));
        assertThat(mapperEdge.calleeNodeId()).isEqualTo(new CallNodeId("mapper"));
        assertThat(mapperEdge.callSite()).isEqualTo(
                new CallSiteRange(TARGET.sourceFile(), 4, 2, 4, 12));
        assertThat(mapperEdge.callExpression()).isEqualTo("findOrder(id)");
        assertThat(mapperEdge.resolutionStrategy()).isEqualTo(ResolutionStrategy.MYBATIS_MAPPER);
        assertThat(mapperEdge.evidence()).containsExactly("mapper evidence");
        assertThat(mapperEdge.availableFollowUps())
                .extracting(followUp -> followUp.toolName(), followUp -> followUp.arguments())
                .containsExactly(
                        tuple(
                                "semantic_get_evidence_source",
                                new SourceDiscoveryMcpDtos.EvidenceSourceInput(
                                        REPOSITORY_ID.value(),
                                        REVISION.value(),
                                        new McpEvidenceIdentityPayload.MapperStatement(
                                                McpMapperIdentityPayloads.toPayload(identity)))),
                        tuple(
                                "semantic_discover_method_implementations",
                                new FrameworkDiscoveryMcpDtos.MethodImplementationsInput(
                                        REPOSITORY_ID.value(), REVISION.value(), DECLARATION_TARGET)));
        CallGraphMcpDtos.GraphEdgeOutput nonMapperEdge = result.edges().get(1);
        assertThat(nonMapperEdge.callerNodeId()).isEqualTo(new CallNodeId("caller"));
        assertThat(nonMapperEdge.calleeNodeId()).isEqualTo(new CallNodeId("local"));
        assertThat(nonMapperEdge.callSite()).isEqualTo(
                new CallSiteRange(TARGET.sourceFile(), 5, 2, 5, 12));
        assertThat(nonMapperEdge.callExpression()).isEqualTo("localCall()");
        assertThat(nonMapperEdge.resolutionStrategy()).isEqualTo(ResolutionStrategy.JDT_CALL_HIERARCHY);
        assertThat(nonMapperEdge.evidence()).containsExactly("jdt evidence");
        assertThat(nonMapperEdge.availableFollowUps()).isEmpty();
    }

    private static OutgoingGraphFragment outgoing(List<GraphEdge> edges) {
        return new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS, REVISION, new CallNodeId("caller"), new GraphTraversal(1, 0, 0, true, GraphLimitReason.NONE),
                nodes(), edges, List.of(), List.of());
    }

    private static IncomingGraphFragment incoming(List<GraphEdge> edges) {
        return new IncomingGraphFragment(
                GraphAnalysisStatus.SUCCESS, REVISION, new CallNodeId("caller"), new GraphTraversal(1, 0, 0, true, GraphLimitReason.NONE),
                nodes(), edges, List.of(), List.of());
    }

    private static List<GraphNode> nodes() {
        return List.of(new GraphNode(
                new CallNodeId("caller"), Optional.of(TARGET), "", NodeContentState.TARGET_ONLY,
                NodeTraversalState.BUDGET_CUTOFF, DispatchKind.SYNCHRONOUS, Optional.empty()));
    }
}
