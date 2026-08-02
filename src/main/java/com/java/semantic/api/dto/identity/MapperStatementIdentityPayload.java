package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.Optional;

/** mapper statement 實體證據的共享 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MapperStatementIdentityPayload(
        @ApiMonitoringField(ApiMonitoringMode.NESTED)
        @NotNull @Valid MapperStatementKeyPayload statementKey,
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String resourcePath,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        Optional<@NotBlank @Size(max = 255) String> databaseId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @PositiveOrZero int documentOrdinal,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "MAPPER_XML_ELEMENT|ANNOTATION_SQL_TEXT") String representation) {

    public MapperStatementIdentityPayload {
        statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
        resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
        databaseId = Objects.requireNonNull(databaseId, "databaseId is required");
        representation = Objects.requireNonNull(representation, "representation is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper statement identity property");
    }
}
