package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Optional;

/** 僅由 typed identity 衍生且不包含來源內容的封閉 evidence 回應 */
public record ConceptEvidenceResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> subject,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<MethodTargetResponse> target,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> resourcePath,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> databaseId,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<Integer> documentOrdinal,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> representation) {

    public ConceptEvidenceResponse {
        subject = Objects.requireNonNull(subject, "subject is required");
        target = Objects.requireNonNull(target, "target is required");
        resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
        databaseId = Objects.requireNonNull(databaseId, "databaseId is required");
        documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
        representation = Objects.requireNonNull(representation, "representation is required");
    }
}
