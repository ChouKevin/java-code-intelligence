package com.java.semantic.model.codefact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Normalizes code-derived search text into deterministic, prefix-searchable tokens. */
public final class CodeFactTokenizer {
    private CodeFactTokenizer() {
    }

    public static List<String> tokenize(String text) {
        String requiredText = Objects.requireNonNull(text, "search text is required");
        String normalized = requiredText.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }
}
