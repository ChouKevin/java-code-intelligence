package com.java.semantic.api.dto.location;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.constraints.Min;

/** HTTP 邊界共用的零基 UTF-16 位置 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PositionPayload(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @Min(0) int line,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @Min(0) int character) {

    public PositionPayload {
        if (line < 0) {
            throw new IllegalArgumentException("line must not be negative");
        }
        if (character < 0) {
            throw new IllegalArgumentException("character must not be negative");
        }
    }
}
