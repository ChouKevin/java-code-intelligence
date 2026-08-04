package com.java.semantic.mcp.dto.source;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads.Fragment;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads.Statement;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** evidence source MCP 查詢的封閉 typed identity */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = McpEvidenceIdentityPayload.AnnotationSql.class, name = "ANNOTATION_SQL"),
        @JsonSubTypes.Type(value = McpEvidenceIdentityPayload.MapperStatement.class, name = "MAPPER_STATEMENT"),
        @JsonSubTypes.Type(value = McpEvidenceIdentityPayload.MapperFragment.class, name = "MAPPER_FRAGMENT")
})
public sealed interface McpEvidenceIdentityPayload permits
        McpEvidenceIdentityPayload.AnnotationSql,
        McpEvidenceIdentityPayload.MapperStatement,
        McpEvidenceIdentityPayload.MapperFragment {

    /** annotation SQL identity */
    record AnnotationSql(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Statement identity)
            implements McpEvidenceIdentityPayload {
    }

    /** mapper statement identity */
    record MapperStatement(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Statement identity)
            implements McpEvidenceIdentityPayload {
    }

    /** mapper fragment identity */
    record MapperFragment(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Fragment identity)
            implements McpEvidenceIdentityPayload {
    }
}
