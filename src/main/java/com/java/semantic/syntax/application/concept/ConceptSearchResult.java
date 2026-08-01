package com.java.semantic.syntax.application.concept;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 綁定實際分析版本的結構化概念搜尋結果 */
public record ConceptSearchResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        /** 保留正規化搜尋條件及原始請求順序供 matchedTerms 與分頁投影 */
        List<ConceptSearchTerm> normalizedTerms,
        /** 保留本次實際搜尋 kind 並固定為 ConceptKind enum 順序 */
        List<ConceptKind> searchedKinds,
        /** 保留本次 provider composition 實際啟用 kind 並固定為 ConceptKind enum 順序 */
        List<ConceptKind> supportedKinds,
        List<ConceptCatalogEntry> candidates,
        ConceptPage page,
        List<SourceExtractionOutcome> coverage,
        List<ConceptIssueSummary> issueSummaries,
        Optional<ConceptSearchQuery> nextPageQuery) {

    public ConceptSearchResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        normalizedTerms = List.copyOf(Objects.requireNonNull(
                normalizedTerms, "normalizedTerms are required"));
        searchedKinds = List.copyOf(Objects.requireNonNull(
                searchedKinds, "searchedKinds are required"));
        supportedKinds = List.copyOf(Objects.requireNonNull(
                supportedKinds, "supportedKinds are required"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        page = Objects.requireNonNull(page, "page is required");
        coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage is required"));
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
        nextPageQuery = Objects.requireNonNull(nextPageQuery, "nextPageQuery is required");
        Assert.notEmpty(normalizedTerms, "normalizedTerms are required");
        Assert.notEmpty(searchedKinds, "searchedKinds are required");
        Assert.notEmpty(supportedKinds, "supportedKinds are required");
        Assert.isTrue(searchedKinds.equals(searchedKinds.stream().sorted().toList()),
                "searchedKinds must follow ConceptKind enum order");
        Assert.isTrue(supportedKinds.equals(supportedKinds.stream().sorted().toList()),
                "supportedKinds must follow ConceptKind enum order");
        Assert.isTrue(supportedKinds.containsAll(searchedKinds),
                "supportedKinds must include searchedKinds");
        if (page.hasMore()) {
            ConceptSearchQuery nextQuery = nextPageQuery.orElseThrow();
            Assert.isTrue(nextQuery.repositoryId().equals(repositoryId),
                    "nextPageQuery repositoryId must match result");
            Assert.isTrue(nextQuery.expectedRevision().equals(analyzedRevision),
                    "nextPageQuery expectedRevision must match analyzedRevision");
            Assert.isTrue(nextQuery.offset() == page.offset() + page.returnedCount(),
                    "nextPageQuery offset must continue from the returned page");
            Assert.isTrue(nextQuery.limit() == page.limit(),
                    "nextPageQuery limit must match result page");
            Assert.isTrue(nextQuery.terms().equals(normalizedTerms),
                    "nextPageQuery terms must match normalizedTerms");
            Assert.isTrue(nextQuery.kinds().equals(Set.copyOf(searchedKinds)),
                    "nextPageQuery kinds must match searchedKinds");
        } else {
            Assert.isTrue(nextPageQuery.isEmpty(), "final page must not contain a nextPageQuery");
        }
    }
}
