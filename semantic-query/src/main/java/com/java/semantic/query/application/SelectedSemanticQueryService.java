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
    private final SourceSliceService sourceSliceService;

    private SelectedSemanticQueryService(CodeFactSearchService searchService, SourceSliceService sourceSliceService) {
        this.searchService = Objects.requireNonNull(searchService, "search service is required");
        this.sourceSliceService = Objects.requireNonNull(sourceSliceService, "source slice service is required");
    }

    public static SelectedSemanticQueryService create(org.springframework.data.mongodb.core.MongoTemplate template,
                                                       CurrentGenerationSelector selector, Duration storageTimeout) {
        CodeFactReadService factReader = new CodeFactReadService(template, selector, storageTimeout);
        CurrentSourceQueryService sourceReader = new CurrentSourceQueryService(template, selector, storageTimeout);
        return new SelectedSemanticQueryService(new CodeFactSearchService(template, selector, storageTimeout),
                new SourceSliceService(sourceReader, factReader));
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
            FactSourceSlice source = sourceSliceService.factSource(selected, new com.java.semantic.model.codefact.CodeFactReadQuery(
                    selected.repositoryId(), selected.revision(), summary.fact().id()), 0);
            items.add(SemanticResultMapper.toProgramElement(summary, source));
        }
        return SemanticResultMapper.toSearchCodeResult(result, items);
    }
}
