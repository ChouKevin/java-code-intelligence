package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.Optional;

/** canonical source type、source file 與 optional method selector */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceSymbolContextRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*"
                + "(?:\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*)*") String type,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        Optional<@Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String> sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @Valid Optional<SourceSymbolMethodContextRequest> method) {

    public SourceSymbolContextRequest {
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        method = Objects.requireNonNull(method, "method is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown context property");
    }
}
