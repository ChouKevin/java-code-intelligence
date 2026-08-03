package com.java.semantic.mcp.dto.identity;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.Optional;

/** MCP 專用 mapper identity transport 集合 */
public final class McpMapperIdentityPayloads {

    private McpMapperIdentityPayloads() {
        throw new UnsupportedOperationException("utility class");
    }

    /** MCP mapper statement logical key */
    public record StatementKey(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String namespace,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String statementId) {
    }

    /** MCP mapper statement evidence identity */
    public record Statement(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid StatementKey statementKey,
            @MonitoringField(MonitoringMode.OMIT) @NotBlank String resourcePath,
            @MonitoringField(MonitoringMode.OMIT) Optional<@NotBlank String> databaseId,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer documentOrdinal,
            @MonitoringField(MonitoringMode.VALUE) @NotNull MapperEvidenceRepresentation representation) {

        public Statement {
            databaseId = Optional.ofNullable(databaseId).orElse(Optional.empty());
        }
    }

    /** MCP mapper fragment evidence identity */
    public record Fragment(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String namespace,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String fragmentId,
            @MonitoringField(MonitoringMode.OMIT) @NotBlank String resourcePath,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer documentOrdinal,
            @MonitoringField(MonitoringMode.VALUE) @NotNull MapperEvidenceRepresentation representation) {
    }

    /** 還原 MCP mapper statement logical key */
    public static MapperStatementKey toDomain(StatementKey payload) {
        StatementKey value = Objects.requireNonNull(payload, "payload is required");
        return new MapperStatementKey(value.namespace(), value.statementId());
    }

    /** 還原 MCP mapper statement evidence identity */
    public static MapperStatementIdentity toDomain(Statement payload) {
        Statement value = Objects.requireNonNull(payload, "payload is required");
        return new MapperStatementIdentity(
                toDomain(value.statementKey()),
                value.resourcePath(),
                value.databaseId(),
                value.documentOrdinal(),
                value.representation());
    }

    /** 還原 MCP mapper fragment evidence identity */
    public static MapperFragmentIdentity toDomain(Fragment payload) {
        Fragment value = Objects.requireNonNull(payload, "payload is required");
        return new MapperFragmentIdentity(
                value.namespace(),
                value.fragmentId(),
                value.resourcePath(),
                value.documentOrdinal(),
                value.representation());
    }
}
