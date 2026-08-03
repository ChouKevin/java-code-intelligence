package com.java.semantic.mcp;

import org.springframework.util.Assert;

/** 表示 MCP 工具呼叫違反已發布的輸入契約 */
public final class McpToolContractException extends RuntimeException {

    private static final String INVALID_TOOL_INPUT = "INVALID_TOOL_INPUT";

    private final String code;

    private McpToolContractException(String code, Throwable cause) {
        super(code, cause);
        Assert.hasText(code, "code is required");
        this.code = code;
    }

    public static McpToolContractException invalidToolInput(Throwable cause) {
        return new McpToolContractException(INVALID_TOOL_INPUT, cause);
    }

    public String code() {
        return code;
    }
}
