package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 已在 snapshot 內 materialize 且受 64 KiB 限制的 Java source segment */
public record JavaSourceSegmentResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangePayload contentRange,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String content,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean contextTruncated,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedUtf8Bytes) {
}
