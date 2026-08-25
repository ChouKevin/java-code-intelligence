package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Flat revision-pinned code-fact search request. Omit filters that are not known. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SearchCodeFactsRequest(
        @NotBlank @Pattern(regexp = RepositoryId.PATTERN) String repositoryId,
        @NotBlank @Pattern(regexp = RepositoryRevision.PATTERN) String revision,
        @NotBlank @Size(min = CodeFactSearchQuery.MIN_QUERY_LENGTH, max = CodeFactSearchQuery.MAX_QUERY_LENGTH) String query,
        Set<CodeFactKind> kinds,
        String packagePrefix,
        @Min(0) Integer offset,
        @Min(1) @Max(100) Integer limit) {
}
