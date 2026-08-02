package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** mapper statement 的邏輯 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MapperStatementKeyPayload(
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String namespace,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{javaISOControl}]+") String statementId) {

    public MapperStatementKeyPayload {
        namespace = Objects.requireNonNull(namespace, "namespace is required");
        statementId = Objects.requireNonNull(statementId, "statementId is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper statement key property");
    }
}
