package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 可作為後續外呼圖輸入的方法實作候選 */
public record MethodImplementationCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean primary,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> qualifiers,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> profiles) {

    public MethodImplementationCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "qualifiers are required"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles are required"));
    }
}
