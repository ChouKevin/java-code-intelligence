package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** 固定 revision 的內部 reference 完整分析與 group 分頁回應 */
public record InternalSourceReferenceResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TargetDeclarationResponse targetDeclaration,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int totalReferenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ReferenceGroupResponse> referenceGroups,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) PageResponse page,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<IssueSummaryResponse> issueSummaries,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps) {

    /** exact target 的宣告證據與可執行來源續讀 */
    public record TargetDeclarationResponse(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) InternalSourceReferenceTargetPayload target,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps) {
    }

    /** 同一 METHOD 或 TYPE context 的 reference 群組 */
    public record ReferenceGroupResponse(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) InternalSourceReferenceContextPayload context,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<RepresentativeReferenceResponse> representativeReferences,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) BoundedResultResponse limits,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps,
            @ApiMonitoringField(ApiMonitoringMode.SIZE)
            List<UnavailableDiscoveryFollowUpResponse> unavailableFollowUps) {
    }

    /** context 已擁有 sourceFile 的代表 reference 範圍 */
    public record RepresentativeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload range,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps) {
    }

    /** 內部 reference 分析損失原因與完整計數 */
    public record IssueSummaryResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String code,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int count) {
    }
}
