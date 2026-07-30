package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** 可供後續分析使用的事件監聽器候選項 */
public record EventListenerCandidateResponse(
        MethodTargetResponse target,
        List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
        SourceRangeResponse sourceRange) {

    public EventListenerCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        listenerAnnotations = List.copyOf(Objects.requireNonNull(
                listenerAnnotations, "listenerAnnotations are required"));
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
    }
}
