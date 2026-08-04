package com.java.semantic.mcp.dto.callgraph;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphError;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.mcp.dto.McpRevisionPinnedInput;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/** 固定 revision call graph MCP 查詢的 transport DTO */
public final class CallGraphMcpDtos {

    private CallGraphMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** call graph 查詢輸入 */
    public record Input(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(2) Integer depth,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTarget target)
            implements McpRevisionPinnedInput {
    }

    /** outgoing call graph 查詢結果 */
    public record OutgoingOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid GraphResult result) {
    }

    /** incoming call graph 查詢結果 */
    public record IncomingOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid GraphResult result) {
    }

    /** MCP 自有完整 graph result */
    public record GraphResult(
            @MonitoringField(MonitoringMode.NESTED) GraphAnalysisStatus status,
            @MonitoringField(MonitoringMode.NESTED) RepositoryRevision analyzedRevision,
            @MonitoringField(MonitoringMode.NESTED) CallNodeId rootNodeId,
            @MonitoringField(MonitoringMode.NESTED) GraphTraversal traversal,
            @MonitoringField(MonitoringMode.NESTED) List<GraphNode> nodes,
            @MonitoringField(MonitoringMode.NESTED) List<GraphEdgeOutput> edges,
            @MonitoringField(MonitoringMode.NESTED) List<GraphWarning> warnings,
            @MonitoringField(MonitoringMode.NESTED) List<GraphError> errors) {
    }

    /** 不直接公開 domain evidence identity 的 edge transport */
    public record GraphEdgeOutput(
            @MonitoringField(MonitoringMode.NESTED) CallNodeId callerNodeId,
            @MonitoringField(MonitoringMode.NESTED) CallNodeId calleeNodeId,
            @MonitoringField(MonitoringMode.NESTED) CallSiteRange callSite,
            @MonitoringField(MonitoringMode.VALUE) String callExpression,
            @MonitoringField(MonitoringMode.NESTED) ResolutionStrategy resolutionStrategy,
            @MonitoringField(MonitoringMode.NESTED) List<String> evidence,
            @MonitoringField(MonitoringMode.NESTED) List<EvidenceSourceFollowUp> availableFollowUps) {
    }

    /** 可直接執行的 evidence source follow-up */
    public record EvidenceSourceFollowUp(
            @MonitoringField(MonitoringMode.VALUE) String toolName,
            @MonitoringField(MonitoringMode.NESTED) SourceDiscoveryMcpDtos.EvidenceSourceInput arguments) {
    }
}
