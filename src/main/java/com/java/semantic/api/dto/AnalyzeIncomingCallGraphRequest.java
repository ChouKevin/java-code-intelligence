package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;

/** Request for a bounded, revision-bound incoming call-graph fragment. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AnalyzeIncomingCallGraphRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @Min(1) @Max(2) Integer depth,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid MethodTargetRequest target) {

    public AnalyzeIncomingCallGraphRequest {
        depth = Objects.requireNonNullElse(depth, 2);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
