package com.java.semantic.query.application;

import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.source.SourceReadContract;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict tagged binding shared by HTTP and MCP. */
public final class RelationQueryInput {
    private RelationQueryInput() { }

    public static RelationContract.Request findRelations(Map<String, ?> input) {
        fields(input, Set.of("context", "indexGeneration", "anchor", "direction", "relationKinds", "resolutionStates", "directory", "limit", "cursor"));
        if (!(input.get("anchor") instanceof Map<?, ?> anchor)) throw new IllegalArgumentException("anchor must be an object");
        RelationContract.AnchorKind kind = RelationContract.AnchorKind.valueOf(string(anchor, "kind"));
        RelationContract.Anchor bound = switch (kind) {
            case SOURCE_SYMBOL -> { fields(anchor, Set.of("kind", "symbolId")); yield new RelationContract.SymbolAnchor(kind, string(anchor, "symbolId")); }
            case EXTERNAL_TARGET -> { fields(anchor, Set.of("kind", "targetId")); yield new RelationContract.ExternalAnchor(kind, string(anchor, "targetId")); }
            case FILE -> { fields(anchor, Set.of("kind", "path")); yield new RelationContract.FileAnchor(kind, string(anchor, "path")); }
        };
        return new RelationContract.Request(SemanticQueryInput.readContext(input.get("context")), string(input, "indexGeneration"), bound,
                RelationContract.Direction.valueOf(string(input, "direction")), enums(input, "relationKinds", RelationContract.Kind.class),
                enums(input, "resolutionStates", RelationContract.ResolutionState.class), optional(input, "directory").orElse(""), limit(input), optional(input, "cursor"));
    }

    private static void fields(Map<?, ?> input, Set<String> allowed) {
        if (Objects.isNull(input)) throw new IllegalArgumentException("an object is required");
        for (Object key : input.keySet()) if (!(key instanceof String) || !allowed.contains(key)) throw new IllegalArgumentException("unknown request field");
    }
    private static String string(Map<?, ?> input, String key) {
        if (!(input.get(key) instanceof String value)) throw new IllegalArgumentException(key + " must be a string");
        return value;
    }
    private static Optional<String> optional(Map<?, ?> input, String key) {
        return input.containsKey(key) ? Optional.of(string(input, key)) : Optional.empty();
    }
    private static <E extends Enum<E>> List<E> enums(Map<?, ?> input, String key, Class<E> type) {
        if (!input.containsKey(key)) return List.of();
        if (!(input.get(key) instanceof List<?> values)) throw new IllegalArgumentException(key + " must be an array");
        return values.stream().map(value -> {
            if (!(value instanceof String name)) throw new IllegalArgumentException(key + " must contain strings");
            return Enum.valueOf(type, name);
        }).distinct().sorted().toList();
    }
    private static int limit(Map<?, ?> input) {
        if (!input.containsKey("limit")) return SourceReadContract.DEFAULT_PAGE_LIMIT;
        if (!(input.get("limit") instanceof Number number)) throw new IllegalArgumentException("limit must be an integer");
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException("limit must be an integer", failure); }
    }
}
