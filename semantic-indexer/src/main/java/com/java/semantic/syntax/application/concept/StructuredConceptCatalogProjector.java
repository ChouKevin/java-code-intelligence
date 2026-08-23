package com.java.semantic.syntax.application.concept;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.java.semantic.syntax.domain.RepositorySyntax;

import org.springframework.util.Assert;

/** 將既有 RepositorySyntax metadata 投影為確定性且以 typed identity 合併的結構化概念目錄 */
public final class StructuredConceptCatalogProjector {

    private final List<ConceptProvider> providers;

    /** 建立第一階段固定 provider 集合 */
    public StructuredConceptCatalogProjector() {
        this(List.of(
                new DeclarationConceptProvider(),
                new TypeUsageConceptProvider(),
                new EntryPointConceptProvider(),
                new MapperStatementConceptProvider()));
    }

    /** 建立可測試的 provider 集合，provider 註冊順序不影響輸出排序 */
    public StructuredConceptCatalogProjector(List<ConceptProvider> providers) {
        List<ConceptProvider> configuredProviders = List.copyOf(Objects.requireNonNull(providers, "providers are required"));
        Set<String> providerIds = new HashSet<>();
        for (ConceptProvider provider : configuredProviders) {
            Assert.isTrue(providerIds.add(provider.providerId()), "duplicate concept providerId");
        }
        this.providers = configuredProviders.stream().sorted(Comparator.comparing(ConceptProvider::providerId)).toList();
    }

    /** 回傳目前 composition 實際啟用且可接受請求的 kinds */
    public Set<ConceptKind> supportedKinds() {
        Set<ConceptKind> supportedKinds = new HashSet<>();
        for (ConceptProvider provider : providers) {
            supportedKinds.addAll(provider.supportedKinds());
        }
        return Set.copyOf(supportedKinds);
    }

    /** 僅投影已存在的 metadata，不讀取來源檔或改變既有 syntax extraction 結果 */
    public StructuredConceptCatalog project(RepositorySyntax syntax) {
        RepositorySyntax repositorySyntax = Objects.requireNonNull(syntax, "syntax is required");
        Map<ConceptIdentity, ConceptCatalogEntry> mergedEntries = new LinkedHashMap<>();
        Map<ConceptIssueReason, Integer> issueCounts = new EnumMap<>(ConceptIssueReason.class);
        for (ConceptProvider provider : providers) {
            ConceptProviderProjection projection = provider.project(repositorySyntax);
            for (ConceptCatalogEntry entry : projection.entries()) {
                Assert.isTrue(provider.supportedKinds().contains(entry.kind()), "provider emitted unsupported concept kind");
                mergedEntries.merge(entry.identity(), entry, ConceptCatalogEntry::merge);
            }
            for (ConceptIssueReason issue : projection.issues()) {
                issueCounts.merge(issue, 1, Integer::sum);
            }
        }
        List<ConceptCatalogEntry> entries = new ArrayList<>(mergedEntries.values());
        entries.sort(ConceptIdentityOrdering.catalogEntryComparator());
        List<ConceptIssueSummary> issues = issueCounts.entrySet().stream()
                .map(entry -> new ConceptIssueSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ConceptIssueSummary::reason))
                .toList();
        return new StructuredConceptCatalog(entries, issues);
    }
}
