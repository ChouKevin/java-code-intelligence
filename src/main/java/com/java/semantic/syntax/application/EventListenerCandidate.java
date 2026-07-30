package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 一個可由呼叫圖使用的事件監聽器方法 */
public record EventListenerCandidate(
        MethodTarget target,
        ListenerSourceLocation sourceLocation,
        List<ListenerAnnotationEvidence> annotationEvidence) {

    public EventListenerCandidate {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(sourceLocation, "sourceLocation is required");
        annotationEvidence = List.copyOf(Objects.requireNonNull(annotationEvidence, "annotationEvidence is required"));
        Assert.notEmpty(annotationEvidence, "annotationEvidence is required");
        Assert.isTrue(target.sourceFile().equals(sourceLocation.sourceFile()),
                "target and sourceLocation sourceFile must match");
    }
}
