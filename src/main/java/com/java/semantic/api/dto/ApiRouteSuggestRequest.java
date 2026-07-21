package com.java.semantic.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApiRouteSuggestRequest(
        @NotBlank String apiPath,
        String httpMethod,
        String repoScope,
        @NotNull @Min(1) @Max(20) Integer limit) {
}
