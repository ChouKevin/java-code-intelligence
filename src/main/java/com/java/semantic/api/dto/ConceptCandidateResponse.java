package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 由 typed ConceptIdentity 唯讀衍生 evidence 與可執行 follow-up 的概念候選回應 */
public record ConceptCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptIdentityResponse identity,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String displayValue,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) /** AND 比對成功的全部正規化搜尋詞並保留請求順序 */
        List<String> matchedTerms,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String authority,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        Optional<ConceptCandidateDetailsResponse> details,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) /** 依精確 typed identity 排序的最小 identity 投影 */
        List<ConceptEvidenceResponse> evidence,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) /** 可直接執行且不需重建參數的候選後續動作 */
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public ConceptCandidateResponse {
        identity = Objects.requireNonNull(identity, "identity is required");
        matchedTerms = List.copyOf(Objects.requireNonNull(
                matchedTerms, "matchedTerms are required"));
        details = Objects.requireNonNull(details, "details is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
