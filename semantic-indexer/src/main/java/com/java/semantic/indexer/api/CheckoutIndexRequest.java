package com.java.semantic.indexer.api;

import jakarta.validation.constraints.NotBlank;

public record CheckoutIndexRequest(@NotBlank String revision) { }
