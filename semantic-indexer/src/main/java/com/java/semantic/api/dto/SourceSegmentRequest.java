package com.java.semantic.api.dto;
import com.java.semantic.model.codefact.SourceRange;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;
import java.util.Optional;

/** 固定 revision 的 bounded canonical source segment 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceSegmentRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$") String expectedRevision,
        @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRangePayload location,
        @MonitoringField(MonitoringMode.VALUE) @Min(0) @Max(20) Integer contextLines) {

    public SourceSegmentRequest {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        location = Objects.requireNonNull(location, "location is required");
        contextLines = Optional.ofNullable(contextLines).orElse(0);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown source segment request property");
    }
}
