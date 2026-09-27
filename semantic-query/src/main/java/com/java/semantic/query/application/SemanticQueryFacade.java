package com.java.semantic.query.application;

import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Transport-neutral application facade for repository, exact-source, and code-fact queries. */
public final class SemanticQueryFacade {
    private final CurrentRepositoryQueryService repositoryQueryService;
    private final GitEvidenceReadService gitEvidenceReadService;
    private final CurrentGenerationSelector currentSelector;
    private final SelectedSemanticQueryService selectedQueries;


    public SemanticQueryFacade(CurrentGenerationSelector currentSelector, SelectedSemanticQueryService selectedQueries,
                               CurrentRepositoryQueryService repositoryQueryService) {
        this(currentSelector, selectedQueries, repositoryQueryService, null);
    }

    public SemanticQueryFacade(CurrentGenerationSelector currentSelector, SelectedSemanticQueryService selectedQueries,
                               CurrentRepositoryQueryService repositoryQueryService, GitEvidenceReadService gitEvidenceReadService) {
        this.repositoryQueryService = Objects.requireNonNull(repositoryQueryService, "repository query service is required");
        this.currentSelector = Objects.requireNonNull(currentSelector, "current generation selector is required");
        this.selectedQueries = Objects.requireNonNull(selectedQueries, "selected semantic queries are required");
        this.gitEvidenceReadService = gitEvidenceReadService;
    }


    public SemanticQueryContract.GitBranchCollection listGitBranches(SemanticQueryContract.GitBranchRequest request) {
        return requireGitEvidenceReader().branches(request);
    }

    public SemanticQueryContract.GitCommitCollection listGitCommits(SemanticQueryContract.GitCommitRequest request) {
        return requireGitEvidenceReader().commits(request);
    }

    public SemanticQueryContract.GitComparisonCollection compareRevisions(SemanticQueryContract.GitComparisonRequest request) {
        return requireGitEvidenceReader().comparisons(request);
    }

    public SemanticQueryContract.GitFileDiffResult getFileDiff(SemanticQueryContract.GitFileDiffRequest request) {
        return requireGitEvidenceReader().fileDiff(request);
    }

    public SemanticQueryContract.GitFileCollection listFiles(SemanticQueryContract.GitFileListRequest request) {
        return requireGitEvidenceReader().listFiles(request);
    }

    public SemanticQueryContract.GitFileContent readFile(SemanticQueryContract.GitFileReadRequest request) {
        return requireGitEvidenceReader().readFile(request);
    }

    public SemanticQueryContract.GitTextSearchResult searchText(SemanticQueryContract.GitTextSearchRequest request) {
        return requireGitEvidenceReader().searchText(request);
    }

    private GitEvidenceReadService requireGitEvidenceReader() {
        if (Objects.isNull(gitEvidenceReadService)) { throw new IndexNotReadyException(); }
        return gitEvidenceReadService;
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
        SelectedGeneration generation = repositoryQueryService.getRepository(requiredRequest.repositoryId());
        return SemanticResultMapper.toRepositoryItem(generation);
    }

    public SemanticQueryContract.SearchCodeResult searchCode(SemanticQueryContract.SearchCodeRequest request) {
        SemanticQueryContract.SearchCodeRequest requiredRequest = Objects.requireNonNull(request, "search code request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(),
                SelectedGenerationGuard.SEARCH_WITH_SOURCES);
        return selectedQueries.searchCode(context, requiredRequest);
    }

    public SemanticQueryContract.FactSourceResult getFactSource(SemanticQueryContract.FactSourceRequest request) {
        SemanticQueryContract.FactSourceRequest requiredRequest = Objects.requireNonNull(request, "fact source request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(),
                SelectedGenerationGuard.SEARCH_WITH_SOURCES);
        return selectedQueries.getFactSource(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult listEntryPoints(SemanticQueryContract.EntryPointRequest request) {
        SemanticQueryContract.EntryPointRequest requiredRequest = Objects.requireNonNull(request, "entry point request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(),
                SelectedGenerationGuard.ENTRY_POINTS);
        return selectedQueries.listEntryPoints(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findApiRoutes(SemanticQueryContract.ApiRouteRequest request) {
        SemanticQueryContract.ApiRouteRequest requiredRequest = Objects.requireNonNull(request, "API route request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(),
                SelectedGenerationGuard.ENTRY_POINTS);
        return selectedQueries.findApiRoutes(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findEventListeners(SemanticQueryContract.EventListenerRequest request) {
        SemanticQueryContract.EventListenerRequest requiredRequest = Objects.requireNonNull(request, "event listener request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SYMBOLS);
        return selectedQueries.findEventListeners(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult listTypeMembers(SemanticQueryContract.TypeMemberRequest request) {
        SemanticQueryContract.TypeMemberRequest requiredRequest = Objects.requireNonNull(request, "type member request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        return selectedQueries.listTypeMembers(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findMethodImplementations(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        return selectedQueries.findMethodImplementations(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findReferences(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        return selectedQueries.findReferences(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findCallers(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        return selectedQueries.findCallers(context, requiredRequest);
    }

    public SemanticQueryContract.CollectionResult findCallees(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        SelectedGeneration context = select(requiredRequest.repositoryId(), requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        return selectedQueries.findCallees(context, requiredRequest);
    }

    private SelectedGeneration select(String repositoryId, String revision,
                                      ProjectionRequirements requirements) {
        return Objects.requireNonNull(currentSelector, "current generation selector is required").select(repositoryId, revision, requirements);
    }
}
