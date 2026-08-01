package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.syntax.domain.SyntaxPosition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** zero-based UTF-16 exact identifier selector */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceSymbolPositionRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotNull @Min(0) Integer line,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotNull @Min(0) Integer character) {

    public SyntaxPosition toDomain() {
        return new SyntaxPosition(line, character);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown position property");
    }
}
