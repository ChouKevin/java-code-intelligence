package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;

/** mapper fragment exact 證據的封閉 identity 回應 */
public record MapperFragmentIdentityResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String namespace,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String fragmentId,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String resourcePath,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int documentOrdinal,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) MapperEvidenceRepresentation representation) {
}
