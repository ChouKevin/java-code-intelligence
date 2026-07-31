package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.syntax.application.ExactContentResult;

import java.util.List;
import java.util.Objects;

/**
 * mapper statement 單一 include 的解析狀態與可執行 fragment 後續動作
 * 完整 fragment identity 來自 typed mapper evidence 而不是重解析回應 XML
 * ambiguity 會保留全部候選，避免 HTTP 投影靜默選取其中一個
 */
public record MapperIncludeResolutionResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String refId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        ExactContentResult.IncludeResolutionStatus status,
        @ApiMonitoringField(ApiMonitoringMode.SIZE)
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MapperIncludeResolutionResponse {
        status = Objects.requireNonNull(status, "status is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
