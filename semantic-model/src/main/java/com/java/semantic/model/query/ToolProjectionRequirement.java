package com.java.semantic.model.query;

import com.java.semantic.model.index.ProjectionRequirements;

import java.util.Objects;
import java.util.Optional;

/** Declares the persisted projections a read-only tool needs from a published generation. */
public record ToolProjectionRequirement(String toolName, Optional<ProjectionRequirements> projections) {

    public ToolProjectionRequirement {
        toolName = Objects.requireNonNull(toolName, "tool name is required");
        projections = Objects.requireNonNull(projections, "projection requirements are required");
    }

    public static ToolProjectionRequirement metadata(String toolName) {
        return new ToolProjectionRequirement(toolName, Optional.empty());
    }

    public static ToolProjectionRequirement generation(String toolName, ProjectionRequirements projections) {
        return new ToolProjectionRequirement(toolName, Optional.of(Objects.requireNonNull(projections, "projection requirements are required")));
    }
}
