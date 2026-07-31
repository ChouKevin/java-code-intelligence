package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 以 AND 語意比對結構化概念 token 或完整 canonicalValue */
public final class ConceptSearchMatcher {

    /** 每個條件都必須比對同一概念項目才會成立 */
    public boolean matches(ConceptCatalogEntry entry, List<ConceptSearchTerm> terms) {
        ConceptCatalogEntry catalogEntry = Objects.requireNonNull(entry, "entry is required");
        List<ConceptSearchTerm> searchTerms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
        return searchTerms.stream().allMatch(term -> matchesTerm(catalogEntry, term));
    }

    private boolean matchesTerm(ConceptCatalogEntry entry, ConceptSearchTerm term) {
        return switch (term.matchMode()) {
            case TOKEN_EXACT -> entry.searchTokens().contains(term.value());
            case TOKEN_PREFIX -> entry.searchTokens().stream().anyMatch(token -> token.startsWith(term.value()));
            case CANONICAL_EXACT -> entry.canonicalValue().toLowerCase(Locale.ROOT).equals(term.value());
        };
    }
}
