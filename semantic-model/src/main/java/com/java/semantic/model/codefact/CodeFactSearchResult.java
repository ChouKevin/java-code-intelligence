package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;

/** One page of search candidates validated against their authoritative facts. */
public record CodeFactSearchResult(
        CurrentGeneration generation,
        CodeFactSearchQuery query,
        List<CodeFactSummary> facts,
        long totalCount,
        boolean hasMore,
        SourceIndexCoverage coverage) {

    public CodeFactSearchResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        query = Objects.requireNonNull(query, "query is required");
        facts = List.copyOf(Objects.requireNonNull(facts, "facts are required"));
        ModelValidation.require(totalCount >= facts.size(), "total count must cover returned facts");
        coverage = Objects.requireNonNull(coverage, "coverage is required");
    }
}
