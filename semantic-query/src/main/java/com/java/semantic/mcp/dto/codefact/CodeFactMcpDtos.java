package com.java.semantic.mcp.dto.codefact;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** MCP payloads retain the same exact revision-pinned contract as the HTTP code-fact surface. */
public final class CodeFactMcpDtos {
    private CodeFactMcpDtos() {
    }

    public record SearchInput(@NotBlank @Pattern(regexp = RepositoryId.PATTERN) String repositoryId,
                              @NotBlank @Pattern(regexp = RepositoryRevision.PATTERN) String revision,
                              @NotBlank @Size(min = CodeFactSearchQuery.MIN_QUERY_LENGTH, max = CodeFactSearchQuery.MAX_QUERY_LENGTH) String query,
                              Set<CodeFactKind> kinds, String packagePrefix, @Min(0) Integer offset,
                              @Min(1) @Max(100) Integer limit) {
    }

    public record GetInput(@NotBlank @Pattern(regexp = RepositoryId.PATTERN) String repositoryId,
                           @NotBlank @Pattern(regexp = RepositoryRevision.PATTERN) String revision,
                           @NotBlank @Pattern(regexp = CodeFactId.PATTERN) String factId) {
    }

    public record SearchOutput(String repositoryId, String revision, CodeFactSearchResult result) {
    }

    public record GetOutput(String repositoryId, String revision, CodeFactDetails result) {
    }
}
