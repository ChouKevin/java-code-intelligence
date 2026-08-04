package com.java.semantic.mcp.mapper;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphError;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.dto.source.McpEvidenceIdentityPayload;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 將 call graph application 結果投影為 MCP transport DTO */
@Component
public final class CallGraphMcpMapper {

    public CallGraphMcpDtos.OutgoingOutput outgoing(RepositoryId repositoryId, OutgoingGraphFragment result) {
        return new CallGraphMcpDtos.OutgoingOutput(result(Objects.requireNonNull(repositoryId, "repositoryId is required"), result));
    }

    public CallGraphMcpDtos.IncomingOutput incoming(RepositoryId repositoryId, IncomingGraphFragment result) {
        return new CallGraphMcpDtos.IncomingOutput(result(Objects.requireNonNull(repositoryId, "repositoryId is required"), result));
    }

    private CallGraphMcpDtos.GraphResult result(RepositoryId repositoryId, OutgoingGraphFragment fragment) {
        return result(repositoryId, fragment.status(), fragment.analyzedRevision(), fragment.rootNodeId(), fragment.traversal(),
                fragment.nodes(), fragment.edges(), fragment.warnings(), fragment.errors());
    }

    private CallGraphMcpDtos.GraphResult result(RepositoryId repositoryId, IncomingGraphFragment fragment) {
        return result(repositoryId, fragment.status(), fragment.analyzedRevision(), fragment.rootNodeId(), fragment.traversal(),
                fragment.nodes(), fragment.edges(), fragment.warnings(), fragment.errors());
    }

    private CallGraphMcpDtos.GraphResult result(
            RepositoryId repositoryId, GraphAnalysisStatus status,
            RepositoryRevision revision, CallNodeId rootNodeId,
            GraphTraversal traversal, List<GraphNode> nodes,
            List<GraphEdge> edges, List<GraphWarning> warnings,
            List<GraphError> errors) {
        return new CallGraphMcpDtos.GraphResult(status, revision, rootNodeId, traversal, nodes,
                edges.stream().map(edge -> new CallGraphMcpDtos.GraphEdgeOutput(
                        edge.callerNodeId(), edge.calleeNodeId(), edge.callSite(), edge.callExpression(), edge.resolutionStrategy(), edge.evidence(),
                        edge.evidenceSourceIdentities().stream().map(identity -> new CallGraphMcpDtos.EvidenceSourceFollowUp(
                                "semantic_get_evidence_source", new SourceDiscoveryMcpDtos.EvidenceSourceInput(
                                        repositoryId.value(), revision.value(), toPayload(EvidenceSourceQuery.identityOf(identity))))).toList())).toList(), warnings, errors);
    }

    private McpEvidenceIdentityPayload toPayload(EvidenceSourceQuery.EvidenceIdentity identity) {
        return new EvidenceIdentityMcpMapper().toPayload(identity);
    }
}
