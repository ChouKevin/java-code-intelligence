package com.java.semantic.model.codefact;

import java.util.List;
import java.util.Objects;

/** Code-proven listener declaration and the qualifying annotation names. */
public record EventListenerCandidate(MethodTarget target, SourceRange location, List<AnnotationFact> annotations) {

    public EventListenerCandidate {
        target = Objects.requireNonNull(target, "target is required");
        location = Objects.requireNonNull(location, "location is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
    }
}
