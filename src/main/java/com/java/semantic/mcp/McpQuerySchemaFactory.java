package com.java.semantic.mcp;

import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.util.Assert;

import java.lang.reflect.Type;

/** 以 Spring AI 的預設規則產生 MCP 工具輸入與輸出 schema */
public final class McpQuerySchemaFactory {

    public String generateForType(Type type) {
        Assert.notNull(type, "type is required");
        return JsonSchemaGenerator.generateForType(type);
    }
}
