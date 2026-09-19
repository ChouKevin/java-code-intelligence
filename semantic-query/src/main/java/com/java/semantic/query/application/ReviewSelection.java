package com.java.semantic.query.application;

import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import java.util.Objects;

/** Immutable semantic context selected from a published review side. */
public record ReviewSelection(ReviewManifestDocument manifest, ReviewSide side, SelectedGeneration selected) {
    public ReviewSelection {
        manifest = Objects.requireNonNull(manifest, "review manifest is required");
        side = Objects.requireNonNull(side, "review side is required");
        selected = Objects.requireNonNull(selected, "selected generation is required");
    }
}
