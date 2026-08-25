package com.java.semantic.api;

import com.java.semantic.api.dto.GetCodeFactRequest;
import com.java.semantic.api.dto.GetCodeFactResponse;
import com.java.semantic.api.dto.SearchCodeFactsRequest;
import com.java.semantic.api.dto.SearchCodeFactsResponse;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Query-only HTTP surface for persisted code facts. */
@RestController
@RequestMapping("/v1/code-facts")
public final class CodeFactController {
    private final CodeFactSearchService searchService;
    private final CodeFactReadService readService;

    public CodeFactController(CodeFactSearchService searchService, CodeFactReadService readService) {
        this.searchService = Objects.requireNonNull(searchService, "search service is required");
        this.readService = Objects.requireNonNull(readService, "read service is required");
    }

    @PostMapping("/search")
    public SearchCodeFactsResponse search(@Valid @RequestBody SearchCodeFactsRequest request) {
        Set<CodeFactKind> kinds = Objects.requireNonNullElse(request.kinds(), Set.of());
        Optional<String> packagePrefix = Optional.ofNullable(request.packagePrefix()).filter(StringUtils::hasText);
        int offset = Objects.requireNonNullElse(request.offset(), CodeFactSearchQuery.DEFAULT_OFFSET);
        int limit = Objects.requireNonNullElse(request.limit(), CodeFactSearchQuery.DEFAULT_LIMIT);
        CodeFactSearchQuery query = new CodeFactSearchQuery(RepositoryId.of(request.repositoryId()), new RepositoryRevision(request.revision()),
                request.query(), kinds, packagePrefix, offset, limit);
        return SearchCodeFactsResponse.from(searchService.search(query));
    }

    @PostMapping("/get")
    public GetCodeFactResponse get(@Valid @RequestBody GetCodeFactRequest request) {
        CodeFactReadQuery query = new CodeFactReadQuery(RepositoryId.of(request.repositoryId()), new RepositoryRevision(request.revision()),
                new CodeFactId(request.factId()));
        return GetCodeFactResponse.from(readService.get(query));
    }
}
