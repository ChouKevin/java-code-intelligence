package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyzeCallGraphRequest(
        @NotBlank String repoId,
        @NotBlank String packageName,
        @NotBlank String className,
        @NotBlank String methodSignature,
        @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision) {
}
