package com.java.semantic.syntax.application.concept;

import java.util.List;

/** RepositorySyntax 的確定性結構化概念目錄與問題摘要 */
public record StructuredConceptCatalog(List<ConceptCatalogEntry> entries, List<ConceptIssueSummary> issueSummaries) {

    public StructuredConceptCatalog {
        entries = List.copyOf(entries);
        issueSummaries = List.copyOf(issueSummaries);
    }
}
