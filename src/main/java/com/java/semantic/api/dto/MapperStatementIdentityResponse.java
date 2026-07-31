package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;

import java.util.Optional;

/** mapper statement exact 證據的封閉 identity 回應 */
public record MapperStatementIdentityResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String namespace,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String statementId,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String resourcePath,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> databaseId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int documentOrdinal,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) MapperEvidenceRepresentation representation) {
}
