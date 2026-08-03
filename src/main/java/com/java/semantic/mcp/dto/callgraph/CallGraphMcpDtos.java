package com.java.semantic.mcp.dto.callgraph;

import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTarget target) {
    }

    /** outgoing call graph 查詢結果 */
    public record OutgoingOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid OutgoingGraphFragment result) {
    }

    /** incoming call graph 查詢結果 */
    public record IncomingOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid IncomingGraphFragment result) {
    }
}
