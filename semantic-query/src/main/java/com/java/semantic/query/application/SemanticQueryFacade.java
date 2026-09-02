package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.query.CurrentGeneration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Transport-neutral application facade for repository, exact-source, and code-fact queries. */
public final class SemanticQueryFacade {
    private final CurrentRepositoryQueryService repositoryQueryService;
    private final CodeFactSearchService codeFactSearchService;
    private final PublishedSourceToolService sourceToolService;

    public SemanticQueryFacade(CurrentRepositoryQueryService repositoryQueryService, CodeFactSearchService codeFactSearchService,
                               PublishedSourceToolService sourceToolService) {
        this.repositoryQueryService = Objects.requireNonNull(repositoryQueryService, "repository query service is required");
        this.codeFactSearchService = Objects.requireNonNull(codeFactSearchService, "code fact search service is required");
        this.sourceToolService = Objects.requireNonNull(sourceToolService, "source tool service is required");
    }

    public SemanticQueryContract.RepositoryCollection listRepositories(SemanticQueryContract.PageRequest request) {
        SemanticQueryContract.PageRequest requiredRequest = Objects.requireNonNull(request, "page request is required");
        List<SemanticQueryContract.RepositoryItem> repositories = repositoryQueryService.listRepositories().stream()
                .sorted(Comparator.comparing(current -> current.repositoryId().value()))
                .map(SemanticResultMapper::toRepositoryItem)
                .toList();
        int start = Math.min(requiredRequest.offset(), repositories.size());
        int end = Math.min(start + requiredRequest.limit(), repositories.size());
        List<SemanticQueryContract.RepositoryItem> items = List.copyOf(repositories.subList(start, end));
        SemanticQueryContract.Page page = new SemanticQueryContract.Page(requiredRequest.offset(), requiredRequest.limit(), items.size(),
                repositories.size(), end < repositories.size());
        return new SemanticQueryContract.RepositoryCollection(items, page);
    }

    public SemanticQueryContract.RepositoryItem getRepository(SemanticQueryContract.RepositoryRequest request) {
        SemanticQueryContract.RepositoryRequest requiredRequest = Objects.requireNonNull(request, "repository request is required");
        CurrentGeneration generation = repositoryQueryService.getRepository(requiredRequest.repositoryId());
        return SemanticResultMapper.toRepositoryItem(generation);
    }

    public SemanticQueryContract.CollectionResult searchCode(SemanticQueryContract.SearchCodeRequest request) {
        SemanticQueryContract.SearchCodeRequest requiredRequest = Objects.requireNonNull(request, "search code request is required");
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), requiredRequest.query(), requiredRequest.kinds(),
                requiredRequest.packagePrefix(), requiredRequest.offset(), requiredRequest.limit());
        CodeFactSearchResult result = codeFactSearchService.search(query);
        List<SemanticQueryContract.ProgramElement> elements = new ArrayList<>();
        for (CodeFactSummary summary : result.facts()) {
            CodeFactReadQuery sourceQuery = new CodeFactReadQuery(result.generation().repositoryId(), result.generation().revision(),
                    summary.fact().id());
            FactSourceSlice source = sourceToolService.factSource(sourceQuery, 0);
            elements.add(SemanticResultMapper.toProgramElement(summary, source));
        }
        return SemanticResultMapper.toCollectionResult(result, elements);
    }

    public SemanticQueryContract.FactSourceResult getFactSource(SemanticQueryContract.FactSourceRequest request) {
        SemanticQueryContract.FactSourceRequest requiredRequest = Objects.requireNonNull(request, "fact source request is required");
        CodeFactReadQuery query = new CodeFactReadQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), new CodeFactId(requiredRequest.factId()));
        FactSourceSlice source = sourceToolService.factSource(query, requiredRequest.contextLines());
        return SemanticResultMapper.toFactSourceResult(requiredRequest.factId(), source);
    }
}
