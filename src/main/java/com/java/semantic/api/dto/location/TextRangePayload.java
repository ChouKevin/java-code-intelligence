package com.java.semantic.api.dto.location;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/** 由外層 identity 提供來源檔案的零基 UTF-16 半開區間 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TextRangePayload(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid PositionPayload start,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid PositionPayload end) {

    public TextRangePayload {
        start = Objects.requireNonNull(start, "start is required");
        end = Objects.requireNonNull(end, "end is required");
    }
}
