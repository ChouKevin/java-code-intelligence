package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 以 AND 語意比對已投影的結構化概念搜尋 token */
public final class ConceptSearchMatcher {

    /** 每個條件都必須比對同一概念項目才會成立 */
    public boolean matches(ConceptSearchDocument document, List<ConceptSearchTerm> terms) {
        ConceptSearchDocument searchDocument = Objects.requireNonNull(document, "document is required");
        List<ConceptSearchTerm> searchTerms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
        return searchTerms.stream().allMatch(term -> matchesTerm(searchDocument, term));
    }

    private boolean matchesTerm(ConceptSearchDocument document, ConceptSearchTerm term) {
        Set<String> termTokens = ConceptSearchTokenizer.tokenizeSearchTerm(term.value());
        return switch (term.matchMode()) {
            case TOKEN_EXACT -> termTokens.stream().allMatch(document.tokens()::contains);
            case TOKEN_PREFIX -> termTokens.stream()
                    .allMatch(termToken -> document.tokens().stream().anyMatch(token -> token.startsWith(termToken)));
        };
    }
}
