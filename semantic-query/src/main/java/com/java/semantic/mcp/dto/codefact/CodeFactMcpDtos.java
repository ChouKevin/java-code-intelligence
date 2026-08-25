package com.java.semantic.mcp.dto.codefact;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** MCP payloads retain the same exact revision-pinned contract as the HTTP code-fact surface. */
public final class CodeFactMcpDtos {
    private CodeFactMcpDtos() {
    }

    public record SearchInput(@NotBlank String repositoryId, @NotBlank String revision, @NotBlank String query,
                              Set<CodeFactKind> kinds, String packagePrefix, @Min(0) Integer offset,
                              @Min(1) @Max(100) Integer limit) {
    }

    public record GetInput(@NotBlank String repositoryId, @NotBlank String revision, @NotBlank String factId) {
    }

    public record SearchOutput(String repositoryId, String revision, CodeFactSearchResult result) {
    }

    public record GetOutput(String repositoryId, String revision, CodeFactDetails result) {
    }
}
