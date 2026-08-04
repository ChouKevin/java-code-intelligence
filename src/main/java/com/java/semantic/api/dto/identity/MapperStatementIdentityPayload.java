package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
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
        @MonitoringField(MonitoringMode.NESTED)
        @NotNull @Valid MapperStatementKeyPayload statementKey,
        @MonitoringField(MonitoringMode.OMIT)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String resourcePath,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.OMIT)
        Optional<@Size(max = 255) @Pattern(regexp = "[^\\p{javaISOControl}]+") String> databaseId,
        @MonitoringField(MonitoringMode.VALUE) @PositiveOrZero int documentOrdinal,
        @MonitoringField(MonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "MAPPER_XML_ELEMENT|ANNOTATION_SQL_TEXT") String representation) {

    public MapperStatementIdentityPayload {
        statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
        resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
        databaseId = Optional.ofNullable(databaseId)
                .orElse(Optional.empty())
                .map(MapperStatementIdentityPayload::requiredDatabaseId);
        representation = Objects.requireNonNull(representation, "representation is required");
    }

    private static String requiredDatabaseId(String value) {
        String database = Objects.requireNonNull(value, "databaseId is required");
        if (database.isBlank()) {
            throw new IllegalArgumentException("databaseId is required");
        }
        return database;
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper statement identity property");
    }
}
