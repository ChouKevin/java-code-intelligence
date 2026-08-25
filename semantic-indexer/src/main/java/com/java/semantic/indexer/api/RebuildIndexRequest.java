package com.java.semantic.indexer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RebuildIndexRequest(boolean authorizeIncompatibleSchema,
                                  @Valid @NotNull GenerationPointerRequest expectedCurrent) { }
