package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 單一 exact source、statement 或 fragment 內容變體回應 */
public record ExactContentVariantResponse(
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        Optional<MapperStatementIdentityResponse> statementIdentity,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        Optional<MapperFragmentIdentityResponse> fragmentIdentity,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        Optional<String> content,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        Optional<String> contentRef,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int utf8ByteCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE)
        List<MapperIncludeResolutionResponse> includeResolutions,
        @ApiMonitoringField(ApiMonitoringMode.SIZE)
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public ExactContentVariantResponse {
        statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required");
        fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
        content = Objects.requireNonNull(content, "content is required");
        contentRef = Objects.requireNonNull(contentRef, "contentRef is required");
        includeResolutions = List.copyOf(Objects.requireNonNull(
                includeResolutions, "includeResolutions are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
