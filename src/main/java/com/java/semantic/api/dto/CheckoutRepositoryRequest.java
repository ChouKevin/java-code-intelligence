package com.java.semantic.api.dto;

import jakarta.validation.constraints.NotBlank;

/** checkout 可接受分支、tag 或 commit SHA */
public record CheckoutRepositoryRequest(@NotBlank String revision) {
}
