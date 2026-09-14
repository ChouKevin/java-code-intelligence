package com.java.semantic.indexer.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

/** Admin request for a direct comparison of two exact immutable commits. */
public record GitComparisonIndexRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{40}") String previous,
        @NotBlank @Pattern(regexp = "[0-9a-f]{40}") String current) { }
