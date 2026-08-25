package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.model.codefact.CodeFactKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** Flat revision-pinned code-fact search request. Omit filters that are not known. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SearchCodeFactsRequest(
        @NotBlank String repositoryId,
        @NotBlank String revision,
        @NotBlank String query,
        Set<CodeFactKind> kinds,
        String packagePrefix,
        @Min(0) Integer offset,
        @Min(1) @Max(100) Integer limit) {
}
