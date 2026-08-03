package com.java.semantic.mcp.dto.route;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.trie.ApiRouteMatchBatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** revision-pinned API route MCP 查詢的 transport DTO */
public final class ApiRouteMcpDtos {

    private ApiRouteMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** API route lookup 輸入 */
    public record LookupInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String apiPath,
            @MonitoringField(MonitoringMode.VALUE) String httpMethod) {
    }

    /** API route suggestion 輸入 */
    public record SuggestInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String apiPath,
            @MonitoringField(MonitoringMode.VALUE) String httpMethod,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(20) Integer limit) {
    }

    /** API route 查詢結果 */
    public record Output(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid ApiRouteMatchBatch result) {
    }
}
