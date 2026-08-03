package com.java.semantic.mcp.dto.framework;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.semantic.application.RevisionBoundMethodImplementations;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import com.java.semantic.syntax.domain.EntryPointType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** framework discovery MCP 查詢的 transport DTO */
public final class FrameworkDiscoveryMcpDtos {

    private FrameworkDiscoveryMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** event listener discovery 輸入 */
    public record EventListenersInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String eventType,
            @MonitoringField(MonitoringMode.VALUE) @Min(0) Integer offset,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(100) Integer limit) {
    }

    /** event listener discovery 結果 */
    public record EventListenersOutput(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RevisionBoundEventListenerDiscovery result) {
    }

    /** method implementation discovery 輸入 */
    public record MethodImplementationsInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTarget declarationTarget) {
    }

    /** method implementation discovery 結果 */
    public record MethodImplementationsOutput(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RevisionBoundMethodImplementations result) {
    }
}
