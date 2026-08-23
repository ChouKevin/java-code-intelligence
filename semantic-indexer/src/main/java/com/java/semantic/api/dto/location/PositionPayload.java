package com.java.semantic.api.dto.location;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.constraints.Min;

/** HTTP 邊界共用的零基 UTF-16 位置 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PositionPayload(
        @MonitoringField(MonitoringMode.VALUE) @Min(0) int line,
        @MonitoringField(MonitoringMode.VALUE) @Min(0) int character) {

    public PositionPayload {
        if (line < 0) {
            throw new IllegalArgumentException("line must not be negative");
        }
        if (character < 0) {
            throw new IllegalArgumentException("character must not be negative");
        }
    }
}
