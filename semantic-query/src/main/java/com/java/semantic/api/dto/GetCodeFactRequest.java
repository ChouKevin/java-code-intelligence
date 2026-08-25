package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Exact revision-pinned fact read; factId must be returned by a previous search. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GetCodeFactRequest(@NotBlank @Pattern(regexp = RepositoryId.PATTERN) String repositoryId,
                                 @NotBlank @Pattern(regexp = RepositoryRevision.PATTERN) String revision,
                                 @NotBlank @Pattern(regexp = CodeFactId.PATTERN) String factId) {
}
