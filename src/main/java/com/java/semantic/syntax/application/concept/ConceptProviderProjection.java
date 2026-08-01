package com.java.semantic.syntax.application.concept;

import java.util.List;

/** 單一 provider 的概念與無法投影 metadata 問題 */
record ConceptProviderProjection(List<ConceptCatalogEntry> entries, List<ConceptIssueReason> issues) {

    ConceptProviderProjection {
        entries = List.copyOf(entries);
        issues = List.copyOf(issues);
    }
}
