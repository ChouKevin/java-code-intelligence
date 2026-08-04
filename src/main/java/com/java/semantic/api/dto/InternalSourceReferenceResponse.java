package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

/** 固定 revision 的內部 reference 完整分析與 group 分頁回應 */
public record InternalSourceReferenceResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.VALUE) String status,
        @MonitoringField(MonitoringMode.NESTED) TargetDeclarationResponse targetDeclaration,
        @MonitoringField(MonitoringMode.VALUE) int totalReferenceCount,
        @MonitoringField(MonitoringMode.SIZE) List<ReferenceGroupResponse> referenceGroups,
        @MonitoringField(MonitoringMode.NESTED) PageResponse page,
        @MonitoringField(MonitoringMode.SIZE) List<IssueSummaryResponse> issueSummaries,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    /** exact target 的宣告證據與可執行來源續讀 */
    public record TargetDeclarationResponse(
            @MonitoringField(MonitoringMode.NESTED) InternalSourceReferenceTargetPayload target,
            @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
            @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {
    }

    /** 同一 METHOD 或 TYPE context 的 reference 群組 */
    public record ReferenceGroupResponse(
            @MonitoringField(MonitoringMode.NESTED) InternalSourceReferenceContextPayload context,
            @MonitoringField(MonitoringMode.SIZE) List<RepresentativeReferenceResponse> representativeReferences,
            @MonitoringField(MonitoringMode.NESTED) BoundedResultResponse limits,
            @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps,
            @MonitoringField(MonitoringMode.SIZE)
            List<UnavailableDiscoveryFollowUpResponse> unavailableFollowUps) {
    }

    /** context 已擁有 sourceFile 的代表 reference 範圍 */
    public record RepresentativeReferenceResponse(
            @MonitoringField(MonitoringMode.NESTED) TextRangePayload range,
            @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {
    }

    /** 內部 reference 分析損失原因與完整計數 */
    public record IssueSummaryResponse(
            @MonitoringField(MonitoringMode.VALUE) String code,
            @MonitoringField(MonitoringMode.VALUE) int count) {
    }
}
