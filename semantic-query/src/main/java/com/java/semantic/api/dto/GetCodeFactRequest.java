package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** Exact revision-pinned fact read; factId must be returned by a previous search. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GetCodeFactRequest(@NotBlank String repositoryId, @NotBlank String revision, @NotBlank String factId) {
}
