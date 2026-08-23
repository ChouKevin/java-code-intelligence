package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** overloaded method 的 canonical MethodTarget retry */
public record SourceMethodContextCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.NESTED) DiscoveryFollowUpResponse retry)
        implements SourceContextCandidateResponse {
}
