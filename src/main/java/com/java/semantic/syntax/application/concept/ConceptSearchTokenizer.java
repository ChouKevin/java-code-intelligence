package com.java.semantic.syntax.application.concept;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.util.StringUtils;

/** 從結構化名稱導出不含來源本文的搜尋 token */
public final class ConceptSearchTokenizer {

    /** 建立概念搜尋斷詞元件 */
    public ConceptSearchTokenizer() {
    }

    /** 將駝峰、分隔字元與路由邊界拆成固定的小寫 token */
    public static Set<String> tokenize(String structuredValue) {
        String value = Objects.requireNonNull(structuredValue, "structuredValue is required")
                .replace("<unresolved>", " ")
                .replaceAll("(?<![\\p{L}\\p{N}])ALL(?![\\p{L}\\p{N}])", " ");
        return tokenizeSearchTerm(value);
    }

    /** 將一個查詢值依相同的識別名稱邊界拆成固定 token，保留合法的 ALL/unresolved 名稱 */
    public static Set<String> tokenizeSearchTerm(String searchTerm) {
        String value = Objects.requireNonNull(searchTerm, "searchTerm is required");
        String boundaries = value
                .replaceAll("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})", " ")
                .replaceAll("(?<=[\\p{Lu}])(?=\\p{Lu}\\p{Ll})", " ");
        String[] fragments = boundaries.split("[^\\p{L}\\p{N}]+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String fragment : fragments) {
            if (StringUtils.hasText(fragment)) {
                tokens.add(fragment.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(tokens);
    }
}
