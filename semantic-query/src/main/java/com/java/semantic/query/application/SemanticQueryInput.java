package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.review.ReviewSide;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict application binding, shared after HTTP or MCP transport decoding. */
public final class SemanticQueryInput {
    private static final Set<String> REPOSITORIES = Set.of("nameFilter", "cursor", "limit");
    private static final Set<String> CONTEXT = Set.of("repositoryId", "selector", "limit");
    private static final Set<String> SEARCH = Set.of("context", "query", "kinds", "packagePrefix", "path", "cursor", "limit");
    private static final Set<String> FILES = Set.of("context", "directory", "nameFilter", "pathFilter", "cursor", "limit");
    private static final Set<String> TEXT = Set.of("context", "query", "directory", "cursor", "limit");
    private static final Set<String> SOURCE = Set.of("context", "target", "maxLines", "cursor");
    private static final Set<String> ENTRY = Set.of("context", "kind", "handlerName", "packagePrefix", "httpMethod", "path",
            "eventType", "destination", "trigger", "cursor", "limit");
    private static final Set<String> OUTLINE = Set.of("context", "target", "kinds", "cursor", "limit");
    private static final Set<String> RELATIONS = Set.of("context", "relation", "factId", "cursor", "limit");
    private static final Set<String> BRANCHES = Set.of("repositoryId", "cursor", "limit");
    private static final Set<String> COMMITS = Set.of("repositoryId", "branch", "cursor", "limit");
    private static final Set<String> COMPARISON = Set.of("comparisonContext", "cursor", "limit");
    private static final Set<String> DIFF = Set.of("comparisonContext", "changeId", "cursor");
    private static final Set<String> CURRENT_CONTEXT = Set.of("kind", "repositoryId", "revision");
    private static final Set<String> REVIEW_CONTEXT = Set.of("kind", "repositoryId", "revision", "reviewId", "side");
    private static final Set<String> CURRENT_SELECTOR = Set.of("kind");
    private static final Set<String> REVIEW_SELECTOR = Set.of("kind", "reviewId");
    private static final Set<String> COMMIT_SELECTOR = Set.of("kind", "revision");
    private static final Set<String> RANGE_SELECTOR = Set.of("kind", "beforeRevision", "afterRevision");
    private static final Set<String> COMPARISON_CONTEXT = Set.of("repositoryId", "reviewId", "before", "after");
    private static final Set<String> EMPTY_ENDPOINT = Set.of("kind");
    private static final Set<String> REVISION_ENDPOINT = Set.of("kind", "revision");
    private static final Set<String> FACT_TARGET = Set.of("kind", "factId", "contextLines");
    private static final Set<String> FILE_TARGET = Set.of("kind", "path", "startLine");
    private static final Set<String> TYPE_OUTLINE = Set.of("kind", "factId");
    private static final Set<String> FILE_OUTLINE = Set.of("kind", "path");
    private static final Set<String> DESTINATION = Set.of("broker", "destination");

    private SemanticQueryInput() { }

    public static SemanticQueryContract.RepositoryRequest repositories(Map<String, ?> input) {
        fields(input, REPOSITORIES);
        return new SemanticQueryContract.RepositoryRequest(optionalString(input, "nameFilter"), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }

    /** URI query strings are converted only here; MCP/application numbers never coerce strings. */
    public static Map<String, Object> repositoryQuery(Map<String, String> input) {
        fields(input, REPOSITORIES);
        Map<String, Object> result = new HashMap<>(input);
        if (input.containsKey("limit")) {
            try {
                result.put("limit", Integer.parseInt(string(input, "limit")));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("limit must be an integer");
            }
        }
        return result;
    }

    public static SemanticQueryContract.ContextRequest getContext(Map<String, ?> input) {
        fields(input, CONTEXT);
        return new SemanticQueryContract.ContextRequest(string(input, "repositoryId"), selector(input.get("selector")),
                integer(input, "limit", SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.SearchCodeRequest searchCode(Map<String, ?> input) {
        fields(input, SEARCH);
        return new SemanticQueryContract.SearchCodeRequest(readContext(input.get("context")), string(input, "query"), kinds(input),
                optionalString(input, "packagePrefix"), optionalString(input, "path"), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.FileListRequest listFiles(Map<String, ?> input) {
        fields(input, FILES);
        return new SemanticQueryContract.FileListRequest(readContext(input.get("context")), optionalString(input, "directory").orElse(""),
                optionalString(input, "nameFilter"), optionalString(input, "pathFilter"), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.TextSearchRequest searchText(Map<String, ?> input) {
        fields(input, TEXT);
        return new SemanticQueryContract.TextSearchRequest(readContext(input.get("context")), string(input, "query"),
                optionalString(input, "directory").orElse(""), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.SourceRequest readSource(Map<String, ?> input) {
        fields(input, SOURCE);
        return new SemanticQueryContract.SourceRequest(readContext(input.get("context")), sourceTarget(input.get("target")),
                integer(input, "maxLines", SemanticQueryContract.DEFAULT_FILE_LINES), optionalString(input, "cursor"));
    }
    public static SemanticQueryContract.EntryPointRequest entryPoints(Map<String, ?> input) {
        fields(input, ENTRY);
        return new SemanticQueryContract.EntryPointRequest(readContext(input.get("context")),
                optionalEnum(input, "kind", SemanticQueryContract.EntryKind.class), optionalString(input, "handlerName"),
                optionalString(input, "packagePrefix"), optionalEnum(input, "httpMethod", SemanticQueryContract.HttpMethod.class),
                optionalString(input, "path"), optionalString(input, "eventType"),
                input.containsKey("destination") ? Optional.of(destination(input.get("destination"))) : Optional.empty(),
                optionalString(input, "trigger"), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.OutlineRequest outline(Map<String, ?> input) {
        fields(input, OUTLINE);
        Map<?, ?> target = object(input.get("target"));
        SemanticQueryContract.OutlineTargetKind kind = enumeration(target, "kind", SemanticQueryContract.OutlineTargetKind.class);
        fields(target, kind == SemanticQueryContract.OutlineTargetKind.TYPE ? TYPE_OUTLINE : FILE_OUTLINE);
        SemanticQueryContract.OutlineTarget selection = new SemanticQueryContract.OutlineTarget(kind,
                optionalString(target, "factId"), optionalString(target, "path"));
        return new SemanticQueryContract.OutlineRequest(readContext(input.get("context")), selection, kinds(input),
                page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.RelationRequest relations(Map<String, ?> input) {
        fields(input, RELATIONS);
        return new SemanticQueryContract.RelationRequest(readContext(input.get("context")),
                enumeration(input, "relation", SemanticQueryContract.RelationMode.class), string(input, "factId"),
                page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.GitBranchRequest branches(Map<String, ?> input) {
        fields(input, BRANCHES);
        return new SemanticQueryContract.GitBranchRequest(string(input, "repositoryId"), page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.GitCommitRequest commits(Map<String, ?> input) {
        fields(input, COMMITS);
        return new SemanticQueryContract.GitCommitRequest(string(input, "repositoryId"), string(input, "branch"),
                page(input, SemanticQueryContract.DEFAULT_COMMIT_LIMIT));
    }
    public static SemanticQueryContract.ComparisonRequest comparison(Map<String, ?> input) {
        fields(input, COMPARISON);
        return new SemanticQueryContract.ComparisonRequest(comparisonContext(input.get("comparisonContext")),
                page(input, SemanticQueryContract.DEFAULT_LIMIT));
    }
    public static SemanticQueryContract.FileDiffRequest fileDiff(Map<String, ?> input) {
        fields(input, DIFF);
        return new SemanticQueryContract.FileDiffRequest(comparisonContext(input.get("comparisonContext")),
                string(input, "changeId"), optionalString(input, "cursor"));
    }

    public static SemanticQueryContract.ReadContext readContext(Object input) {
        Map<?, ?> context = object(input);
        SemanticQueryContract.ContextKind kind = enumeration(context, "kind", SemanticQueryContract.ContextKind.class);
        fields(context, kind == SemanticQueryContract.ContextKind.CURRENT ? CURRENT_CONTEXT : REVIEW_CONTEXT);
        return new SemanticQueryContract.ReadContext(kind, string(context, "repositoryId"), string(context, "revision"),
                optionalString(context, "reviewId"), optionalEnum(context, "side", ReviewSide.class));
    }
    public static SemanticQueryContract.ComparisonContext comparisonContext(Object input) {
        Map<?, ?> context = object(input);
        fields(context, COMPARISON_CONTEXT);
        return new SemanticQueryContract.ComparisonContext(string(context, "repositoryId"), string(context, "reviewId"),
                endpoint(context.get("before")), endpoint(context.get("after")));
    }
    private static SemanticQueryContract.ContextSelector selector(Object input) {
        Map<?, ?> selector = object(input);
        SemanticQueryContract.SelectorKind kind = enumeration(selector, "kind", SemanticQueryContract.SelectorKind.class);
        fields(selector, switch (kind) {
            case CURRENT -> CURRENT_SELECTOR;
            case REVIEW -> REVIEW_SELECTOR;
            case COMMIT -> COMMIT_SELECTOR;
            case RANGE -> RANGE_SELECTOR;
        });
        return new SemanticQueryContract.ContextSelector(kind, optionalString(selector, "reviewId"), optionalString(selector, "revision"),
                optionalString(selector, "beforeRevision"), optionalString(selector, "afterRevision"));
    }
    private static SemanticQueryContract.ComparisonEndpoint endpoint(Object input) {
        Map<?, ?> endpoint = object(input);
        SemanticQueryContract.EndpointKind kind = enumeration(endpoint, "kind", SemanticQueryContract.EndpointKind.class);
        fields(endpoint, kind == SemanticQueryContract.EndpointKind.EMPTY_TREE ? EMPTY_ENDPOINT : REVISION_ENDPOINT);
        return new SemanticQueryContract.ComparisonEndpoint(kind, optionalString(endpoint, "revision"));
    }
    private static SemanticQueryContract.SourceTarget sourceTarget(Object input) {
        Map<?, ?> target = object(input);
        SemanticQueryContract.SourceTargetKind kind = enumeration(target, "kind", SemanticQueryContract.SourceTargetKind.class);
        boolean fact = kind == SemanticQueryContract.SourceTargetKind.FACT;
        fields(target, fact ? FACT_TARGET : FILE_TARGET);
        return new SemanticQueryContract.SourceTarget(kind, optionalString(target, "factId"), optionalString(target, "path"),
                fact ? Optional.empty() : Optional.of(integer(target, "startLine", 1)),
                fact ? Optional.of(integer(target, "contextLines", 0)) : Optional.empty());
    }
    private static ExternalTarget.Destination destination(Object input) {
        Map<?, ?> destination = object(input);
        fields(destination, DESTINATION);
        return new ExternalTarget.Destination(string(destination, "broker"), string(destination, "destination"));
    }
    private static SemanticQueryContract.PageRequest page(Map<?, ?> input, int defaultLimit) {
        return new SemanticQueryContract.PageRequest(optionalString(input, "cursor"), integer(input, "limit", defaultLimit));
    }
    private static Set<CodeFactKind> kinds(Map<?, ?> input) {
        if (!input.containsKey("kinds")) return Set.of();
        if (!(input.get("kinds") instanceof Collection<?> values)) throw new IllegalArgumentException("kinds must be an array");
        EnumSet<CodeFactKind> result = EnumSet.noneOf(CodeFactKind.class);
        for (Object value : values) {
            if (!(value instanceof String text)) throw new IllegalArgumentException("kind must be a string");
            if (!result.add(CodeFactKind.valueOf(text))) throw new IllegalArgumentException("kinds must not repeat");
        }
        return result;
    }
    private static int integer(Map<?, ?> input, String key, int fallback) {
        if (!input.containsKey(key)) return fallback;
        Object value = input.get(key);
        try {
            if (value instanceof Integer integer) return integer;
            if (value instanceof Long number) return Math.toIntExact(number);
            if (value instanceof BigInteger number) return number.intValueExact();
            if (value instanceof BigDecimal number) return number.intValueExact();
            if (value instanceof Number number) return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }
    private static <E extends Enum<E>> E enumeration(Map<?, ?> input, String key, Class<E> type) {
        return Enum.valueOf(type, string(input, key));
    }
    private static <E extends Enum<E>> Optional<E> optionalEnum(Map<?, ?> input, String key, Class<E> type) {
        return input.containsKey(key) ? Optional.of(enumeration(input, key, type)) : Optional.empty();
    }
    private static Optional<String> optionalString(Map<?, ?> input, String key) {
        return input.containsKey(key) ? Optional.of(string(input, key)) : Optional.empty();
    }
    private static String string(Map<?, ?> input, String key) {
        if (!(input.get(key) instanceof String value)) throw new IllegalArgumentException(key + " must be a string");
        return value;
    }
    private static Map<?, ?> object(Object input) {
        if (!(input instanceof Map<?, ?> fields)) throw new IllegalArgumentException("an object is required");
        return fields;
    }
    private static void fields(Map<?, ?> input, Set<String> allowed) {
        if (Objects.isNull(input)) throw new IllegalArgumentException("an object is required");
        for (Object key : input.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) throw new IllegalArgumentException("request contains an unknown field");
        }
    }
}
