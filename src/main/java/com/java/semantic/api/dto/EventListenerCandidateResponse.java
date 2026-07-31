package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 可供後續分析使用的事件監聽器候選項 */
public record EventListenerCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse sourceRange) {

    public EventListenerCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        listenerAnnotations = List.copyOf(Objects.requireNonNull(
                listenerAnnotations, "listenerAnnotations are required"));
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
    }
}
