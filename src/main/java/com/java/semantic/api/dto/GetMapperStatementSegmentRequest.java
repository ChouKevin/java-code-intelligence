package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 重送原始 mapper method authority 的 stateless statement segment 請求
 * contentRef 僅驗證內容一致性且不是 cache lookup key
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GetMapperStatementSegmentRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$")
        String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$")
        String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid MethodTargetRequest target,
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{javaISOControl}]+")
        String contentRef,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @PositiveOrZero int segmentIndex) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper statement segment request property");
    }
}
