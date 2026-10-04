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
    private static final Set<String> FILES = Set.of("context", "directory", "cursor", "limit");
    private static final Set<String> TEXT = Set.of("context", "query", "directory", "filePattern", "limit");
    private static final Set<String> SOURCE = Set.of("context", "path", "startLine", "maxLines", "cursor");
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
                integer(input, "limit", SourceReadContract.DEFAULT_PAGE_LIMIT), optionalString(input, "cursor"));
    }

    public static TextSearchRequest searchText(Map<String, ?> input) {
        fields(input, TEXT);
        return new TextSearchRequest(readContext(input.get("context")), string(input, "query"),
                optionalString(input, "directory").orElse(""), optionalString(input, "filePattern"),
                integer(input, "limit", SourceReadContract.DEFAULT_PAGE_LIMIT));
    }

    public static ReadSourceRequest readSource(Map<String, ?> input) {
        fields(input, SOURCE);
        return new ReadSourceRequest(readContext(input.get("context")), string(input, "path"),
                integer(input, "startLine", 1), integer(input, "maxLines", SourceReadContract.DEFAULT_READ_LINES),
                optionalString(input, "cursor"));
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
