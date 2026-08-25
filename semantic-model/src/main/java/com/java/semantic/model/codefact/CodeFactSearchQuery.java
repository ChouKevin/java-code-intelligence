package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, revision-pinned query over the derived code-fact search projection. */
public record CodeFactSearchQuery(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        String query,
        Set<CodeFactKind> kinds,
        Optional<String> packagePrefix,
        int offset,
        int limit) {

    public static final int DEFAULT_OFFSET = 0;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MIN_QUERY_LENGTH = 2;
    public static final int MAX_QUERY_LENGTH = 256;
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    public CodeFactSearchQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        query = ModelValidation.requiredText(query, "query");
        ModelValidation.require(query.length() >= MIN_QUERY_LENGTH && query.length() <= MAX_QUERY_LENGTH,
                "query length must be between 2 and 256 characters");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
        packagePrefix = Objects.requireNonNull(packagePrefix, "package prefix is required")
                .map(CodeFactSearchQuery::packagePrefix);
        offset = requireOffset(offset);
        limit = requireLimit(limit);
    }

    public CodeFactSearchQuery(RepositoryId repositoryId, RepositoryRevision revision, String query) {
        this(repositoryId, revision, query, Set.of(), Optional.empty(), DEFAULT_OFFSET, DEFAULT_LIMIT);
    }

    private static int requireOffset(int value) {
        ModelValidation.require(value >= 0, "offset must not be negative");
        return value;
    }

    private static int requireLimit(int value) {
        ModelValidation.require(value >= 1 && value <= 100, "limit must be between 1 and 100");
        return value;
    }

    private static String packagePrefix(String value) {
        String prefix = ModelValidation.requiredText(value, "package prefix");
        ModelValidation.require(PACKAGE_PREFIX.matcher(prefix).matches(), "package prefix must be a Java package prefix");
        return prefix;
    }
}
