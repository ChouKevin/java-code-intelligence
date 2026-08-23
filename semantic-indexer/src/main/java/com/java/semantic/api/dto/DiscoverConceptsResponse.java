package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的結構化概念探索回應 */
public record DiscoverConceptsResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.SIZE) /** 正規化搜尋詞並保留原始請求順序 */
        List<String> normalizedTerms,
        @MonitoringField(MonitoringMode.SIZE) /** 本次實際搜尋 kind 並固定為 enum 順序 */
        List<String> searchedKinds,
        @MonitoringField(MonitoringMode.SIZE) /** 服務目前支援的全部 kind 並固定為 enum 順序 */
        List<String> supportedKinds,
        @MonitoringField(MonitoringMode.SIZE) /** 明示結構化探索不搜尋來源本文的固定限制 */
        List<String> limitations,
        @MonitoringField(MonitoringMode.SIZE) List<ConceptCandidateResponse> candidates,
        @MonitoringField(MonitoringMode.NESTED) PageResponse page,
        @MonitoringField(MonitoringMode.NESTED) ConceptCoverageResponse coverage,
        @MonitoringField(MonitoringMode.SIZE) List<ConceptIssueSummaryResponse> issueSummaries,
        @MonitoringField(MonitoringMode.NESTED) /** 可直接執行的分頁後續動作 */
        List<DiscoveryFollowUpResponse> availableFollowUps,
        @MonitoringField(MonitoringMode.SIZE) /** 無結果且無下一頁時提供不含 API/request 的精煉指引 */
        List<UnavailableDiscoveryFollowUpResponse> unavailableFollowUps) {

    public DiscoverConceptsResponse {
        normalizedTerms = List.copyOf(Objects.requireNonNull(
                normalizedTerms, "normalizedTerms are required"));
        searchedKinds = List.copyOf(Objects.requireNonNull(
                searchedKinds, "searchedKinds are required"));
        supportedKinds = List.copyOf(Objects.requireNonNull(
                supportedKinds, "supportedKinds are required"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        page = Objects.requireNonNull(page, "page is required");
        coverage = Objects.requireNonNull(coverage, "coverage is required");
        issueSummaries = List.copyOf(Objects.requireNonNull(
                issueSummaries, "issueSummaries are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
        unavailableFollowUps = List.copyOf(Objects.requireNonNull(
                unavailableFollowUps, "unavailableFollowUps are required"));
    }
}
