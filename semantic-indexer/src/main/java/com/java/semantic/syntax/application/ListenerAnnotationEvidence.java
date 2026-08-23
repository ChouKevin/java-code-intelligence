package com.java.semantic.syntax.application;

import java.util.Objects;

/** 已驗證監聽器註解的種類與辨識依據 */
public record ListenerAnnotationEvidence(ListenerAnnotationKind kind, AnnotationMatchKind matchKind) {

    public ListenerAnnotationEvidence {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(matchKind, "matchKind is required");
    }
}
