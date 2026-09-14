package com.java.semantic.indexer.api;

import jakarta.validation.constraints.Pattern;

/** Admin request for a direct comparison of two exact immutable commits. */
public record GitComparisonIndexRequest(
        @Pattern(regexp = "[0-9a-f]{40}") String previous,
        @Pattern(regexp = "[0-9a-f]{40}") String current) { }
