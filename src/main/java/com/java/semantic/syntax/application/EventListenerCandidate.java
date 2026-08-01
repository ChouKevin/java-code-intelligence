package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 一個可由呼叫圖使用的事件監聽器方法 */
public record EventListenerCandidate(
        MethodTarget target,
        SourceRange declarationRange,
        List<ListenerAnnotationEvidence> annotationEvidence) {

    public EventListenerCandidate {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(declarationRange, "declarationRange is required");
        annotationEvidence = List.copyOf(Objects.requireNonNull(annotationEvidence, "annotationEvidence is required"));
        Assert.notEmpty(annotationEvidence, "annotationEvidence is required");
        Assert.isTrue(target.sourceFile().equals(declarationRange.sourceFile()),
                "target and declarationRange sourceFile must match");
    }
}
