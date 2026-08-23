package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Objects;
import java.util.Optional;

/**
 * 受 65,536 UTF-8 bytes 上限的 canonical 來源區段
 * location 與 nextLocation 使用零基 UTF-16 半開區間，nextLocation 指向尚未回傳的 suffix
 */
public record SourceSegmentPayload(
        @MonitoringField(MonitoringMode.NESTED) SourceRangePayload location,
        @MonitoringField(MonitoringMode.OMIT) String content,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.NESTED) Optional<SourceRangePayload> nextLocation) {

    public SourceSegmentPayload {
        location = Objects.requireNonNull(location, "location is required");
        content = Objects.requireNonNull(content, "content is required");
        nextLocation = Objects.requireNonNull(nextLocation, "nextLocation is required");
    }
}
