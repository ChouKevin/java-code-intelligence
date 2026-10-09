package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceReadContract;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Shared strict binding after either transport has decoded JSON. */
public final class IndexQueryInput {
    private IndexQueryInput() { }

    public static IndexContract.SearchCodeRequest searchCode(Map<String, ?> input) {
        fields(input, Set.of("context", "query", "match", "kinds", "owner", "directory", "limit", "cursor", "indexGeneration"));
        return new IndexContract.SearchCodeRequest(SemanticQueryInput.readContext(input.get("context")),
                string(input, "query"), IndexContract.Match.valueOf(string(input, "match")),
                enums(input, "kinds", IndexContract.DeclarationKind.class), optional(input, "owner"),
                optional(input, "directory").orElse(""), limit(input), optional(input, "cursor"), optional(input, "indexGeneration"));
    }

    public static IndexContract.OutlineRequest outline(Map<String, ?> input) {
        fields(input, Set.of("context", "path", "limit", "cursor", "indexGeneration"));
        return new IndexContract.OutlineRequest(SemanticQueryInput.readContext(input.get("context")), string(input, "path"),
                limit(input), optional(input, "cursor"), optional(input, "indexGeneration"));
    }

    public static IndexContract.EntryPointsRequest entryPoints(Map<String, ?> input) {
        fields(input, Set.of("context", "candidateKinds", "directory", "limit", "cursor", "indexGeneration"));
        return new IndexContract.EntryPointsRequest(SemanticQueryInput.readContext(input.get("context")),
                enums(input, "candidateKinds", IndexContract.CandidateKind.class), optional(input, "directory").orElse(""),
                limit(input), optional(input, "cursor"), optional(input, "indexGeneration"));
    }

    private static <E extends Enum<E>> List<E> enums(Map<String, ?> input, String key, Class<E> type) {
        if (!input.containsKey(key)) return List.of();
        if (!(input.get(key) instanceof List<?> values)) throw new IllegalArgumentException(key + " must be an array");
        return values.stream().map(value -> {
            if (!(value instanceof String name)) throw new IllegalArgumentException(key + " must contain strings");
            return Enum.valueOf(type, name);
        }).distinct().sorted().toList();
    }

    private static int limit(Map<String, ?> input) {
        if (!input.containsKey("limit")) return SourceReadContract.DEFAULT_PAGE_LIMIT;
        if (!(input.get("limit") instanceof Number number)) throw new IllegalArgumentException("limit must be an integer");
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException exception) { throw new IllegalArgumentException("limit must be an integer", exception); }
    }

    private static Optional<String> optional(Map<String, ?> input, String key) {
        return input.containsKey(key) ? Optional.of(string(input, key)) : Optional.empty();
    }

    private static String string(Map<String, ?> input, String key) {
        if (!(input.get(key) instanceof String value)) throw new IllegalArgumentException(key + " must be a string");
        return value;
    }

    private static void fields(Map<String, ?> input, Set<String> allowed) {
        if (Objects.isNull(input)) throw new IllegalArgumentException("an object is required");
        for (Object key : input.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) throw new IllegalArgumentException("request contains an unknown field");
        }
    }
}
