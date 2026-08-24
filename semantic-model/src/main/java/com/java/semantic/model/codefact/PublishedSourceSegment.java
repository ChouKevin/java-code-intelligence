package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;

import java.util.Objects;
import java.util.Optional;

/** Authorized stored-content slice; no parser-derived interpretation is added. */
public record PublishedSourceSegment(CurrentGeneration generation, SourceRange location, String content,
                                     Optional<CodeFactIdentity> evidenceIdentity, Optional<SourceRange> nextLocation,
                                     boolean contextTruncated) {
    public PublishedSourceSegment {
        generation = Objects.requireNonNull(generation, "generation is required");
        location = Objects.requireNonNull(location, "location is required");
        content = Objects.requireNonNull(content, "content is required");
        evidenceIdentity = Objects.requireNonNull(evidenceIdentity, "evidence identity is required");
        nextLocation = Objects.requireNonNull(nextLocation, "next location is required");
    }
}
