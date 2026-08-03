package com.java.semantic.mcp.dto.source;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceMember;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceType;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** internal reference MCP 查詢的封閉 exact declaration target */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = McpExactSourceDeclarationTargetPayload.Type.class, name = "TYPE"),
        @JsonSubTypes.Type(value = McpExactSourceDeclarationTargetPayload.Method.class, name = "METHOD"),
        @JsonSubTypes.Type(value = McpExactSourceDeclarationTargetPayload.Member.class, name = "MEMBER")
})
public sealed interface McpExactSourceDeclarationTargetPayload permits
        McpExactSourceDeclarationTargetPayload.Type,
        McpExactSourceDeclarationTargetPayload.Method,
        McpExactSourceDeclarationTargetPayload.Member {

    /** 型別 declaration target */
    record Type(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType identity)
            implements McpExactSourceDeclarationTargetPayload {
    }

    /** 方法 declaration target */
    record Method(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid McpJavaIdentityPayloads.Method identity)
            implements McpExactSourceDeclarationTargetPayload {
    }

    /** 成員 declaration target */
    record Member(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceMember identity)
            implements McpExactSourceDeclarationTargetPayload {
    }
}
