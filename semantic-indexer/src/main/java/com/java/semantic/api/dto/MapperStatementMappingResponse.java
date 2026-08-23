package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 保留 statement identity、typed mapping status 與全部確定 method candidates 的封閉投影 */
public record MapperStatementMappingResponse(
        @MonitoringField(MonitoringMode.NESTED) MapperStatementKeyPayload statement,
        @MonitoringField(MonitoringMode.VALUE) String status,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.VALUE) Optional<String> reason,
        @MonitoringField(MonitoringMode.SIZE)
        List<MapperMethodCandidateResponse> candidates) {

    public MapperStatementMappingResponse {
        statement = Objects.requireNonNull(statement, "statement is required");
        reason = Objects.requireNonNull(reason, "reason is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
    }
}
