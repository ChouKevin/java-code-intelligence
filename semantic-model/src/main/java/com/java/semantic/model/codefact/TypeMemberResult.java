package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.index.SourceIndexCoverage;

import java.util.List;
import java.util.Objects;

/** Deterministically ordered, current-generation declaration members. */
public record TypeMemberResult(CurrentGeneration generation, TypeMemberQuery query, List<CodeFactSummary> members,
                               long totalCount, boolean hasMore, SourceIndexCoverage coverage) {
    public TypeMemberResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        query = Objects.requireNonNull(query, "query is required");
        members = List.copyOf(Objects.requireNonNull(members, "members are required"));
        if (totalCount < members.size()) { throw new IllegalArgumentException("total count must include returned members"); }
        coverage = Objects.requireNonNull(coverage, "coverage is required");
    }
}
