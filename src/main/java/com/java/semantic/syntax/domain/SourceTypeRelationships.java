package com.java.semantic.syntax.domain;

import java.util.List;

/** 來源型別直接宣告的繼承關係證據 */
public record SourceTypeRelationships(
        List<NominalTypeReference> extendedTypes,
        List<NominalTypeReference> implementedTypes) {

    public SourceTypeRelationships {
        extendedTypes = List.copyOf(extendedTypes);
        implementedTypes = List.copyOf(implementedTypes);
    }
}
