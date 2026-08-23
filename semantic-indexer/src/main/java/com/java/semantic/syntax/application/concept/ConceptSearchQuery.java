package com.java.semantic.syntax.application.concept;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.lang.model.SourceVersion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 綁定儲存庫版本的結構化概念搜尋請求 */
public record ConceptSearchQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        List<ConceptSearchTerm> terms,
        Set<ConceptKind> kinds,
        Optional<String> packagePrefix,
        int offset,
        int limit) {

    private static final int MAXIMUM_TERMS = 4;

    private static final int MINIMUM_LIMIT = 1;

    private static final int MAXIMUM_LIMIT = 100;

    public ConceptSearchQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
        packagePrefix = normalizePackagePrefix(packagePrefix);
        Assert.isTrue(!terms.isEmpty() && terms.size() <= MAXIMUM_TERMS,
                "terms must contain between 1 and " + MAXIMUM_TERMS + " values");
        Assert.isTrue(!kinds.isEmpty(), "kinds are required");
        Assert.isTrue(offset >= 0, "offset must not be negative");
        Assert.isTrue(limit >= MINIMUM_LIMIT && limit <= MAXIMUM_LIMIT,
                "limit must be between " + MINIMUM_LIMIT + " and " + MAXIMUM_LIMIT);
    }

    /** 保留舊內部測試呼叫；HTTP 邊界只接受一個 packagePrefix */
    public ConceptSearchQuery(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            List<ConceptSearchTerm> terms,
            Set<ConceptKind> kinds,
            Set<String> packageFilters,
            int offset,
            int limit) {
        this(
                repositoryId,
                expectedRevision,
                terms,
                kinds,
                optionalPackagePrefix(packageFilters),
                offset,
                limit);
    }

    /** 建立保留其餘固定版本條件的下一頁查詢 */
    public ConceptSearchQuery nextPage(int nextOffset) {
        return new ConceptSearchQuery(
                repositoryId, expectedRevision, terms, kinds, packagePrefix, nextOffset, limit);
    }

    private static Optional<String> normalizePackagePrefix(Optional<String> value) {
        return Objects.requireNonNull(value, "packagePrefix is required")
                .map(ConceptSearchQuery::normalizePackagePrefix);
    }

    private static Optional<String> optionalPackagePrefix(Set<String> packageFilters) {
        Set<String> filters = Objects.requireNonNull(packageFilters, "packageFilters are required");
        Assert.isTrue(filters.size() <= 1, "only one packagePrefix is supported");
        return filters.stream().findFirst();
    }

    private static String normalizePackagePrefix(String candidate) {
        String value = Objects.requireNonNull(candidate, "packagePrefix is required").strip();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        Assert.isTrue(StringUtils.hasText(value), "packagePrefix is required");
        Assert.isTrue(value.codePoints().noneMatch(Character::isISOControl),
                "packagePrefix must not contain control characters");
        Assert.isTrue(SourceVersion.isName(value), "packagePrefix must be a Java package name");
        return value;
    }
}
