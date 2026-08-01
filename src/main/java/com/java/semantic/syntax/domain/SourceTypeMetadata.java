package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 一個儲存庫來源型別的完整語法中繼資料 */
public record SourceTypeMetadata(
        SourceTypeDeclaration declaration,
        SourceTypeRelationships relationships,
        SourceTypeMembers members,
        FrameworkTypeFacts frameworkFacts,
        CompilationUnitContext compilationUnit) {

    public SourceTypeMetadata {
        declaration = Objects.requireNonNull(declaration, "declaration is required");
        relationships = Objects.requireNonNull(relationships, "relationships is required");
        members = Objects.requireNonNull(members, "members is required");
        frameworkFacts = Objects.requireNonNull(frameworkFacts, "frameworkFacts is required");
        compilationUnit = Objects.requireNonNull(compilationUnit, "compilationUnit is required");
    }
}
