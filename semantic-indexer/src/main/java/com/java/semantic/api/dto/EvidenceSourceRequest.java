package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 固定 repository revision 的 typed evidence source 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvidenceSourceRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$") String expectedRevision,
        @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid EvidenceSourceIdentityPayload identity) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown evidence source request property");
    }
}
