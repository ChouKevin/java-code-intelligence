package com.java.semantic.indexer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record GitHistoryIndexRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String catalogId,
        @NotBlank String branch,
        @NotBlank @Pattern(regexp = "[0-9a-f]{40}") String revision) { }
