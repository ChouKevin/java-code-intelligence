package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.List;
import java.util.Objects;

/** 可供後續分析使用的事件監聽器候選項 */
public record EventListenerCandidateResponse(
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.SIZE) List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload sourceRange,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public EventListenerCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        listenerAnnotations = List.copyOf(Objects.requireNonNull(
                listenerAnnotations, "listenerAnnotations are required"));
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
