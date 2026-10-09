package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.ContextRequest;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.RepositoryRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict application binding, shared after HTTP or MCP transport decoding. */
public final class SemanticQueryInput {

    private static final Set<String> REPOSITORIES = Set.of("nameFilter", "cursor", "limit");
    private static final Set<String> CONTEXT = Set.of("repositoryId", "revision");
    private static final Set<String> FILES = Set.of("context", "directory", "cursor", "limit", "depth");
    private static final Set<String> TEXT = Set.of("context", "query", "directory", "filePattern", "limit");
    private static final Set<String> SOURCE = Set.of("context", "path", "view", "startLine", "ranges",
            "tailLines", "maxLines", "contextBefore", "contextAfter", "indexGeneration", "cursor");
    private static final Set<String> SOURCE_CONTEXT = Set.of("repositoryId", "revision");

    private SemanticQueryInput() { }

    public static RepositoryRequest repositories(Map<String, ?> input) {
        fields(input, REPOSITORIES);
        return new RepositoryRequest(optionalString(input, "nameFilter"),
                integer(input, "limit", SourceReadContract.DEFAULT_PAGE_LIMIT), optionalString(input, "cursor"));
    }

    /** URI query strings are converted only here; MCP/application numbers never coerce strings. */
    public static Map<String, Object> repositoryQuery(Map<String, String> input) {
        fields(input, REPOSITORIES);
        Map<String, Object> result = new HashMap<>(input);
        if (input.containsKey("limit")) {
            try {
                result.put("limit", Integer.parseInt(string(input, "limit")));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("limit must be an integer", exception);
            }
        }
        return result;
    }

    public static ContextRequest getContext(Map<String, ?> input) {
        fields(input, CONTEXT);
        return new ContextRequest(string(input, "repositoryId"), optionalString(input, "revision"));
    }

    public static FileListRequest listFiles(Map<String, ?> input) {
        fields(input, FILES);
        return new FileListRequest(readContext(input.get("context")), optionalString(input, "directory").orElse(""),
                integer(input, "depth", 1), integer(input, "limit", SourceReadContract.DEFAULT_PAGE_LIMIT), optionalString(input, "cursor"));
    }

    public static TextSearchRequest searchText(Map<String, ?> input) {
        fields(input, TEXT);
        return new TextSearchRequest(readContext(input.get("context")), string(input, "query"),
                optionalString(input, "directory").orElse(""), optionalString(input, "filePattern"),
                integer(input, "limit", SourceReadContract.DEFAULT_PAGE_LIMIT));
    }

    public static ReadSourceRequest readSource(Map<String, ?> input) {
        fields(input, SOURCE);
        SourceReadContract.View view;
        try { view = SourceReadContract.View.valueOf(optionalString(input, "view").orElse("auto").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("view must be auto, raw or summary", exception); }
        if (input.containsKey("view") && !Set.of("auto", "raw", "summary").contains(string(input, "view"))) {
            throw new IllegalArgumentException("view must be auto, raw or summary");
        }
        java.util.List<SourceReadContract.LineRange> ranges = new java.util.ArrayList<>();
        if (input.containsKey("ranges")) {
            if (!(input.get("ranges") instanceof java.util.List<?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("ranges must be a nonempty array");
            }
            if (values.size() > 20) {
                throw new IllegalArgumentException("at most twenty ranges are allowed");
            }
            for (Object value : values) {
                Map<?, ?> range = object(value);
                fields(range, Set.of("startLine", "endLine"));
                if (!range.containsKey("startLine") || !range.containsKey("endLine")) {
                    throw new IllegalArgumentException("range requires both endpoints");
                }
                ranges.add(new SourceReadContract.LineRange(integer(range, "startLine", 0), integer(range, "endLine", 0)));
            }
        }
        boolean contextDefaults = view == SourceReadContract.View.AUTO && ranges.size() == 1;
        return new ReadSourceRequest(readContext(input.get("context")), string(input, "path"), view,
                input.containsKey("startLine") ? Optional.of(integer(input, "startLine", 0)) : Optional.empty(),
                ranges, input.containsKey("tailLines") ? Optional.of(integer(input, "tailLines", 0)) : Optional.empty(),
                integer(input, "maxLines", SourceReadContract.DEFAULT_READ_LINES),
                integer(input, "contextBefore", contextDefaults ? 1 : 0),
                integer(input, "contextAfter", contextDefaults ? 3 : 0),
                optionalString(input, "indexGeneration"), optionalString(input, "cursor"));
    }

    public static SourceContext readContext(Object input) {
        Map<?, ?> context = object(input);
        fields(context, SOURCE_CONTEXT);
        return new SourceContext(string(context, "repositoryId"), string(context, "revision"));
    }

    private static int integer(Map<?, ?> input, String key, int fallback) {
        if (!input.containsKey(key)) {
            return fallback;
        }
        Object value = input.get(key);
        try {
            if (value instanceof Integer integer) {
                return integer;
            }
            if (value instanceof Long number) {
                return Math.toIntExact(number);
            }
            if (value instanceof BigInteger number) {
                return number.intValueExact();
            }
            if (value instanceof BigDecimal number) {
                return number.intValueExact();
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).intValueExact();
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    private static Optional<String> optionalString(Map<?, ?> input, String key) {
        return input.containsKey(key) ? Optional.of(string(input, key)) : Optional.empty();
    }

    private static String string(Map<?, ?> input, String key) {
        if (!(input.get(key) instanceof String value)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value;
    }

    private static Map<?, ?> object(Object input) {
        if (!(input instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("an object is required");
        }
        return fields;
    }

    private static void fields(Map<?, ?> input, Set<String> allowed) {
        if (Objects.isNull(input)) {
            throw new IllegalArgumentException("an object is required");
        }
        for (Object key : input.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw new IllegalArgumentException("request contains an unknown field");
            }
        }
    }
}
