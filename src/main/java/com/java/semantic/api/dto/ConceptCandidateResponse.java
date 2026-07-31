package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 由 typed ConceptIdentity 唯讀衍生 evidence 與可執行 follow-up 的概念候選回應 */
public record ConceptCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String canonicalValue,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String displayValue,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) /** AND 比對成功的全部正規化搜尋詞並保留請求順序 */
        List<String> matchedTerms,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> declaringType,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String authority,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> subject,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<MethodTargetResponse> target,
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        Optional<MapperStatementMappingResponse> mapperStatementMapping,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) /** 依 kind 與 canonicalOrderKey 排序的最小 typed identity 投影 */
        List<ConceptEvidenceResponse> evidence,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) /** 可直接執行且不需重建參數的候選後續動作 */
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public ConceptCandidateResponse {
        matchedTerms = List.copyOf(Objects.requireNonNull(
                matchedTerms, "matchedTerms are required"));
        declaringType = Objects.requireNonNull(declaringType, "declaringType is required");
        subject = Objects.requireNonNull(subject, "subject is required");
        target = Objects.requireNonNull(target, "target is required");
        mapperStatementMapping = Objects.requireNonNull(
                mapperStatementMapping, "mapperStatementMapping is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
