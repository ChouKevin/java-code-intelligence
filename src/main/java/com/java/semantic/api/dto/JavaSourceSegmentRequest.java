package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;
import java.util.Optional;

/** 固定 revision 的 bounded Java source segment 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JavaSourceSegmentRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid SourceRangePayload sourceRange,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @Min(0) @Max(20) Integer contextLines) {

    public JavaSourceSegmentRequest {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
        contextLines = Optional.ofNullable(contextLines).orElse(3);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown Java source segment request property");
    }
}
