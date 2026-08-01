package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 精確 typed concept identity resolve 的封閉 HTTP 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ResolveConceptRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid ConceptIdentityResponse identity) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown resolve concept request property");
    }
}
