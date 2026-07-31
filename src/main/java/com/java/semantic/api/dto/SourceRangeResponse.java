package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import org.springframework.util.Assert;

import java.util.Objects;

/** Start-inclusive, end-exclusive source range using zero-based UTF-16 positions. */
public record SourceRangeResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) PositionResponse start,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) PositionResponse end) {

    public SourceRangeResponse {
        Assert.hasText(sourceFile, "sourceFile must not be blank");
        start = Objects.requireNonNull(start, "start is required");
        end = Objects.requireNonNull(end, "end is required");
    }
}
