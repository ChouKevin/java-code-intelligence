package com.java.semantic.mcp.dto.repository;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.EntryPointType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Set;

/** repository catalog 與 entry point MCP 查詢的 transport DTO */
public final class RepositoryMcpDtos {

    private RepositoryMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** 無引數的 repository catalog 查詢 */
    public record ListInput() {
    }

    /** repository catalog 查詢結果 */
    public record ListOutput(@MonitoringField(MonitoringMode.NESTED) List<@Valid RepositoryStatus> repositories) {
    }

    /** 讀取單一 repository catalog 項目的輸入 */
    public record GetInput(@MonitoringField(MonitoringMode.VALUE) @NotBlank
                           @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId) {
    }

    /** 單一 repository catalog 查詢結果 */
    public record GetOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RepositoryStatus repository) {
    }

    /** 固定 revision 的 entry point 查詢輸入 */
    public record EntryPointsInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.SIZE) @NotNull Set<@NotNull EntryPointType> types) {
    }

    /** entry point MCP 查詢結果 */
    public record EntryPointsOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RevisionBoundEntryPoints result) {
    }
}
