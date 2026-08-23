package com.java.semantic.mcp;

import java.util.List;

/** 供應同一業務垂直範圍內的 MCP 查詢工具註冊 */
public interface McpQueryProvider {

    List<? extends McpQueryRegistration<?, ?>> registrations();
}
