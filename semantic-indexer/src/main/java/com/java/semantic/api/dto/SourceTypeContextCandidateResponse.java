package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** duplicate source type 的 exact file retry */
public record SourceTypeContextCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.VALUE) String sourceFile,
        @MonitoringField(MonitoringMode.NESTED) DiscoveryFollowUpResponse retry)
        implements SourceContextCandidateResponse {
}
