package com.java.semantic.semantic.application;

import java.util.List;
import java.util.Objects;

/** 同一精確 METHOD 或 TYPE context 的 reference 群組 */
public record InternalReferenceGroup(
        InternalReferenceContext context,
        List<InternalReferenceOccurrence> representativeReferences,
        InternalReferenceGroupLimits limits) {

    public InternalReferenceGroup {
        context = Objects.requireNonNull(context, "context is required");
        representativeReferences = List.copyOf(Objects.requireNonNull(
                representativeReferences, "representativeReferences are required"));
        limits = Objects.requireNonNull(limits, "limits are required");
        if (representativeReferences.size() != limits.returnedCount()) {
            throw new IllegalArgumentException("representativeReferences must match returnedCount");
        }
    }
}
