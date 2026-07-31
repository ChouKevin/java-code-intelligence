package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 固定 repository revision 的 exact content 封閉回應 */
public record ExactContentResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ExactContentVariantResponse> variants) {

    public ExactContentResponse {
        variants = List.copyOf(Objects.requireNonNull(variants, "variants are required"));
    }
}
