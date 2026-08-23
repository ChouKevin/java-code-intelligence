package com.java.semantic.syntax.domain;

import java.util.List;

/** 型別層級的框架 annotation 與 bean 宣告證據 */
public record FrameworkTypeFacts(
        List<AnnotationEvidence> annotations,
        List<String> profiles,
        boolean primary,
        List<String> beanQualifiers) {

    public FrameworkTypeFacts {
        annotations = List.copyOf(annotations);
        profiles = List.copyOf(profiles);
        beanQualifiers = List.copyOf(beanQualifiers);
    }
}
