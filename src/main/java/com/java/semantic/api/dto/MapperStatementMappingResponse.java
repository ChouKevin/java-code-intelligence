package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 保留 statement identity、typed mapping status 與全部確定 method candidates 的封閉投影 */
public record MapperStatementMappingResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MapperStatementKeyPayload statement,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> reason,
        @ApiMonitoringField(ApiMonitoringMode.SIZE)
        List<MapperMethodCandidateResponse> candidates) {

    public MapperStatementMappingResponse {
        statement = Objects.requireNonNull(statement, "statement is required");
        reason = Objects.requireNonNull(reason, "reason is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
    }
}
