package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 方法實作探索的封閉 HTTP 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DiscoverMethodImplementationsRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid MethodTargetRequest declarationTarget) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
