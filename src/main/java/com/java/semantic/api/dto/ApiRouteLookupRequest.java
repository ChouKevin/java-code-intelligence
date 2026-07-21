package com.java.semantic.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ApiRouteLookupRequest(
        @NotBlank String apiPath,
        String httpMethod,
        String repoScope) {
}
