package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materializes semantic query results against one generation selected at facade admission. */
public final class SelectedSemanticQueryService {
    private final CodeFactSearchService searchService;
    private final CurrentSourceQueryService sourceService;

    private SelectedSemanticQueryService(CodeFactSearchService searchService, CurrentSourceQueryService sourceService) {
        this.searchService = Objects.requireNonNull(searchService, "search service is required");
        this.sourceService = Objects.requireNonNull(sourceService, "source service is required");
    }

    public static SelectedSemanticQueryService create(org.springframework.data.mongodb.core.MongoTemplate template,
                                                       CurrentGenerationSelector selector, Duration storageTimeout) {
        CurrentSourceQueryService sourceReader = new CurrentSourceQueryService(template, selector, storageTimeout);
        return new SelectedSemanticQueryService(new CodeFactSearchService(template, selector, storageTimeout), sourceReader);
    }

    public SemanticQueryContract.SearchCodeResult searchCode(SelectedGeneration context, SemanticQueryContract.SearchCodeRequest request) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        SemanticQueryContract.SearchCodeRequest requiredRequest = Objects.requireNonNull(request, "search request is required");
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), requiredRequest.query(), requiredRequest.kinds(),
                requiredRequest.packagePrefix(), requiredRequest.offset(), requiredRequest.limit());
        CodeFactSearchResult result = searchService.search(selected, query);
        List<SemanticQueryContract.ProgramElement> items = new ArrayList<>();
        for (CodeFactSummary summary : result.facts()) {
            String content = sourceService.getSource(selected, summary.location().sourceFile()).utf8Content();
            FactSourceSlice source = new FactSourceSlice(selected, summary.location(), summary.location(), content);
            items.add(SemanticResultMapper.toProgramElement(summary, source));
        }
        return SemanticResultMapper.toSearchCodeResult(result, items);
    }
}
