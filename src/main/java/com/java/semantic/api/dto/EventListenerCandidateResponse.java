package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.List;
import java.util.Objects;

/** 可供後續分析使用的事件監聽器候選項 */
public record EventListenerCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload sourceRange) {

    public EventListenerCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        listenerAnnotations = List.copyOf(Objects.requireNonNull(
                listenerAnnotations, "listenerAnnotations are required"));
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
    }
}
