package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;
import java.util.Optional;

/** Unicode code-point-safe exact content segment 的封閉回應 */
public record ExactContentSegmentResponse(
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String contentRef,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentIndex,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int utf8ByteCount,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String content,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        Optional<DiscoveryFollowUpResponse> nextSegmentFollowUp) {

    public ExactContentSegmentResponse {
        nextSegmentFollowUp = Objects.requireNonNull(
                nextSegmentFollowUp, "nextSegmentFollowUp is required");
    }
}
