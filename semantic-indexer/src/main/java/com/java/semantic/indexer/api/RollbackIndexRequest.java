package com.java.semantic.indexer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RollbackIndexRequest(@NotNull @Valid GenerationPointerRequest expectedCurrent,
                                   @NotNull @Valid GenerationPointerRequest expectedRollback) { }
