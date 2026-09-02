package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.query.CurrentGeneration;

import java.util.List;
import java.util.Objects;

/** Maps already-authorized query results to the transport-neutral application contract. */
public final class SemanticResultMapper {

    private SemanticResultMapper() {
    }

    public static SemanticQueryContract.RepositoryItem toRepositoryItem(CurrentGeneration generation) {
        CurrentGeneration requiredGeneration = Objects.requireNonNull(generation, "generation is required");
        return new SemanticQueryContract.RepositoryItem(requiredGeneration.repositoryId().value(), requiredGeneration.revision().value());
    }

    public static SemanticQueryContract.ProgramElement toProgramElement(CodeFactSummary summary, FactSourceSlice slice) {
        CodeFactSummary requiredSummary = Objects.requireNonNull(summary, "code fact summary is required");
        FactSourceSlice requiredSlice = Objects.requireNonNull(slice, "fact source slice is required");
        return new SemanticQueryContract.ProgramElement(requiredSummary.fact().id().value(), requiredSummary.fact().identity().kind(),
                requiredSummary.fact().identity().canonicalIdentity().canonicalForm(),
                SourceSnippetMapper.toSnippet(requiredSlice.sourceRange(), requiredSlice.fileContent()));
    }

    public static SemanticQueryContract.CollectionResult toCollectionResult(CodeFactSearchResult result,
                                                                              List<SemanticQueryContract.ProgramElement> elements) {
        CodeFactSearchResult requiredResult = Objects.requireNonNull(result, "code fact search result is required");
        List<SemanticQueryContract.ProgramElement> requiredElements = List.copyOf(Objects.requireNonNull(elements, "program elements are required"));
        CurrentGeneration generation = requiredResult.generation();
        SemanticQueryContract.Page page = new SemanticQueryContract.Page(requiredResult.query().offset(), requiredResult.query().limit(),
                requiredElements.size(), requiredResult.totalCount(), requiredResult.hasMore());
        return new SemanticQueryContract.CollectionResult(generation.repositoryId().value(), generation.revision().value(), requiredElements, page);
    }

    public static SemanticQueryContract.FactSourceResult toFactSourceResult(String factId, FactSourceSlice slice) {
        String requiredFactId = Objects.requireNonNull(factId, "fact id is required");
        FactSourceSlice requiredSlice = Objects.requireNonNull(slice, "fact source slice is required");
        CurrentGeneration generation = requiredSlice.generation();
        return new SemanticQueryContract.FactSourceResult(generation.repositoryId().value(), generation.revision().value(), requiredFactId,
                SourceSnippetMapper.toSnippet(requiredSlice.sourceRange(), requiredSlice.fileContent()),
                SourceSnippetMapper.toFactRange(requiredSlice.factRange()));
    }
}
