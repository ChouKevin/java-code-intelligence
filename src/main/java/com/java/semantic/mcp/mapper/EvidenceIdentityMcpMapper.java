package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads;
import com.java.semantic.mcp.dto.source.McpEvidenceIdentityPayload;
import com.java.semantic.syntax.application.EvidenceSourceQuery;

import java.util.Objects;

/** MCP 與 domain evidence identity 的唯一雙向轉換邊界 */
public final class EvidenceIdentityMcpMapper {

    public EvidenceSourceQuery.EvidenceIdentity toDomain(McpEvidenceIdentityPayload payload) {
        return switch (Objects.requireNonNull(payload, "payload is required")) {
            case McpEvidenceIdentityPayload.AnnotationSql value -> new EvidenceSourceQuery.AnnotationSql(
                    McpMapperIdentityPayloads.toDomain(value.identity()));
            case McpEvidenceIdentityPayload.MapperStatement value -> new EvidenceSourceQuery.MapperStatement(
                    McpMapperIdentityPayloads.toDomain(value.identity()));
            case McpEvidenceIdentityPayload.MapperFragment value -> new EvidenceSourceQuery.MapperFragment(
                    McpMapperIdentityPayloads.toDomain(value.identity()));
        };
    }

    public McpEvidenceIdentityPayload toPayload(EvidenceSourceQuery.EvidenceIdentity identity) {
        return switch (Objects.requireNonNull(identity, "identity is required")) {
            case EvidenceSourceQuery.AnnotationSql value -> new McpEvidenceIdentityPayload.AnnotationSql(
                    McpMapperIdentityPayloads.toPayload(value.identity()));
            case EvidenceSourceQuery.MapperStatement value -> new McpEvidenceIdentityPayload.MapperStatement(
                    McpMapperIdentityPayloads.toPayload(value.identity()));
            case EvidenceSourceQuery.MapperFragment value -> new McpEvidenceIdentityPayload.MapperFragment(
                    McpMapperIdentityPayloads.toPayload(value.identity()));
        };
    }
}
