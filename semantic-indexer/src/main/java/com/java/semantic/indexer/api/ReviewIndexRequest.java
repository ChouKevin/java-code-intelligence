package com.java.semantic.indexer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Exact commit requested as the immutable B side of a current-to-commit review. */
public record ReviewIndexRequest(
        @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$") String revision) {
}
