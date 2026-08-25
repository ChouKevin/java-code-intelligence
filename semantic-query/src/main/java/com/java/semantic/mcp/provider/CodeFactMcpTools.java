package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.dto.codefact.CodeFactMcpDtos;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Query-only MCP implementation for the persisted flat code-fact search and exact-read tools. */
@Component
public final class CodeFactMcpTools {
    private final CodeFactSearchService searchService;
    private final CodeFactReadService readService;

    public CodeFactMcpTools(CodeFactSearchService searchService, CodeFactReadService readService) {
        this.searchService = Objects.requireNonNull(searchService, "search service is required");
        this.readService = Objects.requireNonNull(readService, "read service is required");
    }

    public CodeFactMcpDtos.SearchOutput search(CodeFactMcpDtos.SearchInput input) {
        Set<CodeFactKind> kinds = Objects.requireNonNullElse(input.kinds(), Set.of());
        Optional<String> packagePrefix = Optional.ofNullable(input.packagePrefix()).filter(StringUtils::hasText);
        int offset = Objects.requireNonNullElse(input.offset(), CodeFactSearchQuery.DEFAULT_OFFSET);
        int limit = Objects.requireNonNullElse(input.limit(), CodeFactSearchQuery.DEFAULT_LIMIT);
        CodeFactSearchQuery query = new CodeFactSearchQuery(RepositoryId.of(input.repositoryId()), new RepositoryRevision(input.revision()),
                input.query(), kinds, packagePrefix, offset, limit);
        com.java.semantic.model.codefact.CodeFactSearchResult result = searchService.search(query);
        return new CodeFactMcpDtos.SearchOutput(result.generation().repositoryId().value(), result.generation().revision().value(), result);
    }

    public CodeFactMcpDtos.GetOutput get(CodeFactMcpDtos.GetInput input) {
        CodeFactReadQuery query = new CodeFactReadQuery(RepositoryId.of(input.repositoryId()), new RepositoryRevision(input.revision()),
                new CodeFactId(input.factId()));
        com.java.semantic.model.codefact.CodeFactDetails result = readService.get(query);
        return new CodeFactMcpDtos.GetOutput(result.generation().repositoryId().value(), result.generation().revision().value(), result);
    }
}
