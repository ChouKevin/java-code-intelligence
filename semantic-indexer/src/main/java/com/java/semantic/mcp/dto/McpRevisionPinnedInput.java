package com.java.semantic.mcp.dto;

/** 提供 MCP adapter 擷取固定 revision request identity 的輸入契約 */
public interface McpRevisionPinnedInput extends McpRepositoryScopedInput {

    String expectedRevision();
}
