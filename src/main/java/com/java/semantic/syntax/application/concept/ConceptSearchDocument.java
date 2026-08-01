package com.java.semantic.syntax.application.concept;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 僅供概念搜尋比對使用且不會進入 identity 或 HTTP 回應的衍生文件 */
public record ConceptSearchDocument(Set<String> tokens) {

    public ConceptSearchDocument {
        TreeSet<String> orderedTokens = new TreeSet<>(Objects.requireNonNull(tokens, "tokens are required"));
        tokens = Collections.unmodifiableSet(new LinkedHashSet<>(orderedTokens));
    }
}
