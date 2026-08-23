package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

/** 固定 repository revision 與 exact target 的內部 reference 查詢 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record InternalSourceReferenceRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid
        InternalSourceReferenceTargetPayload target,
        @MonitoringField(MonitoringMode.VALUE) @Min(0) int offset,
        @MonitoringField(MonitoringMode.VALUE) @Min(1) @Max(100) Integer limit) {

    public InternalSourceReferenceRequest {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        target = Objects.requireNonNull(target, "target is required");
        limit = Optional.ofNullable(limit).orElse(20);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown internal reference request property");
    }
}
