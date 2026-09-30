package com.java.semantic.model.codefact;

import com.java.semantic.model.query.SelectedGeneration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative exact fact payload, deliberately excluding graph and business interpretation. */
public record CodeFactDetails(
        SelectedGeneration generation,
        CodeFact fact,
        SourceRange location,
        List<AnnotationFact> annotations,
        Optional<MapperStatementKind> mapperStatementKind) {

    public CodeFactDetails {
        generation = Objects.requireNonNull(generation, "generation is required");
        fact = Objects.requireNonNull(fact, "code fact is required");
        location = Objects.requireNonNull(location, "location is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        mapperStatementKind = Objects.requireNonNull(mapperStatementKind, "mapper statement kind is required");
        if ((fact.identity().kind() == CodeFactKind.MAPPER_STATEMENT) != mapperStatementKind.isPresent()) {
            throw new IllegalArgumentException("mapper statement metadata must match fact kind");
        }
    }
}
