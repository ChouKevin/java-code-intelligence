package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 由 typed ConceptIdentity 唯讀衍生 evidence 與可執行 follow-up 的概念候選回應 */
public record ConceptCandidateResponse(
        @MonitoringField(MonitoringMode.NESTED) ConceptIdentityResponse identity,
        @MonitoringField(MonitoringMode.VALUE) String displayValue,
        @MonitoringField(MonitoringMode.SIZE) /** AND 比對成功的全部正規化搜尋詞並保留請求順序 */
        List<String> matchedTerms,
        @MonitoringField(MonitoringMode.VALUE) String authority,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.NESTED)
        Optional<ConceptCandidateDetailsResponse> details,
        @MonitoringField(MonitoringMode.SIZE) /** 依精確 typed identity 排序的最小 identity 投影 */
        List<ConceptEvidenceResponse> evidence,
        @MonitoringField(MonitoringMode.NESTED) /** 可直接執行且不需重建參數的候選後續動作 */
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
