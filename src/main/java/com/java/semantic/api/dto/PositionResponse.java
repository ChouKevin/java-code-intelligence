package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import org.springframework.util.Assert;

/** Zero-based UTF-16 source coordinate. */
public record PositionResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) int line, @ApiMonitoringField(ApiMonitoringMode.VALUE) int character) {

    public PositionResponse {
        Assert.isTrue(line >= 0, "line must not be negative");
        Assert.isTrue(character >= 0, "character must not be negative");
    }
}
