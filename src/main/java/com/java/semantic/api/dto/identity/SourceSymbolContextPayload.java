package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.Optional;

/** Java 型別與可選來源及方法限制的 source-symbol HTTP selector */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceSymbolContextPayload(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid JavaTypeIdentityPayload javaType,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        Optional<@Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String> sourceFile,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @Valid Optional<SourceSymbolMethodContextPayload> method) {

    public SourceSymbolContextPayload {
        javaType = Objects.requireNonNull(javaType, "javaType is required");
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        method = Objects.requireNonNull(method, "method is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown context property");
    }
}
