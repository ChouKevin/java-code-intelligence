package com.java.semantic.indexer.api;

import jakarta.validation.constraints.NotBlank;

public record GitHistoryIndexRequest(@NotBlank String catalogId, @NotBlank String branch, @NotBlank String revision) { }
