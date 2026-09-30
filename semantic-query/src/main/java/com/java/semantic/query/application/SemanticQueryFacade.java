package com.java.semantic.query.application;

import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.query.application.ReadContextSelector.Access;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/** The sole application dispatch contract shared by HTTP and MCP. */
public final class SemanticQueryFacade {
    private static final ProjectionRequirements FACT_SOURCE = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SEARCH));
    private static final ProjectionRequirements DECLARATIONS = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS));
    private static final ProjectionRequirements TYPE_OUTLINE = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.SEARCH));
    private static final ProjectionRequirements ENTRIES = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.ENTRY_POINTS));
    private static final ProjectionRequirements RELATIONS = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.SEARCH, ProjectionName.RELATIONS));
    private final ContextDiscoveryService discovery;
    private final ReadContextSelector contexts;
    private final SelectedSemanticQueryService semantic;
    private final GitEvidenceReadService git;

    public SemanticQueryFacade(ContextDiscoveryService discovery, ReadContextSelector contexts,
            SelectedSemanticQueryService semantic, GitEvidenceReadService git) {
        this.discovery = Objects.requireNonNull(discovery);
        this.contexts = Objects.requireNonNull(contexts);
        this.semantic = Objects.requireNonNull(semantic);
        this.git = Objects.requireNonNull(git);
    }

    public Object execute(String operation, Map<String, ?> input) {
        return switch (Objects.requireNonNull(operation, "operation is required")) {
            case "list_repositories" -> listRepositories(SemanticQueryInput.repositories(input));
            case "get_context" -> getContext(SemanticQueryInput.getContext(input));
            case "search_code" -> searchCode(SemanticQueryInput.searchCode(input));
            case "list_files" -> listFiles(SemanticQueryInput.listFiles(input));
            case "search_text" -> searchText(SemanticQueryInput.searchText(input));
            case "read_source" -> readSource(SemanticQueryInput.readSource(input));
            case "list_entry_points" -> listEntryPoints(SemanticQueryInput.entryPoints(input));
            case "get_outline" -> getOutline(SemanticQueryInput.outline(input));
            case "find_relations" -> findRelations(SemanticQueryInput.relations(input));
            case "list_git_branches" -> listGitBranches(SemanticQueryInput.branches(input));
            case "list_git_commits" -> listGitCommits(SemanticQueryInput.commits(input));
            case "compare_revisions" -> compareRevisions(SemanticQueryInput.comparison(input));
            case "get_file_diff" -> getFileDiff(SemanticQueryInput.fileDiff(input));
            default -> throw new IllegalArgumentException("unknown query operation");
        };
    }

    public SemanticQueryContract.RepositoryCollection listRepositories(SemanticQueryContract.RepositoryRequest request) {
        return discovery.listRepositories(request);
    }
    public SemanticQueryContract.ContextResult getContext(SemanticQueryContract.ContextRequest request) {
        return discovery.getContext(request);
    }
    public SemanticQueryContract.FactCollection searchCode(SemanticQueryContract.SearchCodeRequest request) {
        return semantic.searchCode(contexts.select(request.context(), semantic.searchRequirements(request), Access.SEMANTIC), request);
    }
    public SemanticQueryContract.FileCollection listFiles(SemanticQueryContract.FileListRequest request) {
        return git.listFiles(contexts.select(request.context(), ReadContextSelector.SOURCE_ONLY, Access.WHOLE_SOURCE), request);
    }
    public SemanticQueryContract.TextSearchResult searchText(SemanticQueryContract.TextSearchRequest request) {
        return git.searchText(contexts.select(request.context(), ReadContextSelector.SOURCE_ONLY, Access.WHOLE_SOURCE), request);
    }
    public SemanticQueryContract.SourceResult readSource(SemanticQueryContract.SourceRequest request) {
        boolean fact = request.target().kind() == SemanticQueryContract.SourceTargetKind.FACT;
        return git.readSource(contexts.select(request.context(), fact ? FACT_SOURCE : ReadContextSelector.SOURCE_ONLY,
                fact ? Access.SEMANTIC : Access.WHOLE_SOURCE), request);
    }
    public SemanticQueryContract.EntryPointCollection listEntryPoints(SemanticQueryContract.EntryPointRequest request) {
        ProjectionRequirements requirements = request.kind().filter(kind -> kind == SemanticQueryContract.EntryKind.EVENT)
                .map(kind -> DECLARATIONS).orElse(ENTRIES);
        return semantic.listEntryPoints(contexts.select(request.context(), requirements, Access.SEMANTIC), request);
    }
    public SemanticQueryContract.FactCollection getOutline(SemanticQueryContract.OutlineRequest request) {
        ProjectionRequirements requirements = request.target().kind() == SemanticQueryContract.OutlineTargetKind.TYPE ? TYPE_OUTLINE : DECLARATIONS;
        return semantic.getOutline(contexts.select(request.context(), requirements, Access.SEMANTIC), request);
    }
    public SemanticQueryContract.RelationCollection findRelations(SemanticQueryContract.RelationRequest request) {
        return semantic.findRelations(contexts.select(request.context(), RELATIONS, Access.SEMANTIC), request);
    }
    public SemanticQueryContract.GitBranchCollection listGitBranches(SemanticQueryContract.GitBranchRequest request) {
        return git.listGitBranches(request);
    }
    public SemanticQueryContract.GitCommitCollection listGitCommits(SemanticQueryContract.GitCommitRequest request) {
        return git.listGitCommits(request);
    }
    public SemanticQueryContract.ComparisonResult compareRevisions(SemanticQueryContract.ComparisonRequest request) {
        return git.compareRevisions(contexts.selectComparison(request.comparisonContext()), request);
    }
    public SemanticQueryContract.FileDiffResult getFileDiff(SemanticQueryContract.FileDiffRequest request) {
        return git.getFileDiff(contexts.selectComparison(request.comparisonContext()), request);
    }
}
