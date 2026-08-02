package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 以五欄 MethodTarget 讀取所有 mapper statement 變體的封閉請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GetMapperStatementRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$")
        String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$")
        String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid MethodTargetPayload target) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper statement request property");
    }
}
