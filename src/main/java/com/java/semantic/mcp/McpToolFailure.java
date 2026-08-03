package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.util.Assert;

/** MCP 工具已知失敗的安全結構化回應 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolFailure(
        String errorCode,
        String message,
        Recovery recovery,
        String expectedRevision,
        String currentRevision) {

    public McpToolFailure {
        Assert.hasText(errorCode, "errorCode is required");
        Assert.hasText(message, "message is required");
        Assert.notNull(recovery, "recovery is required");
    }

    /** MCP client 可安全據以決定是否等待後重試的資訊 */
    public record Recovery(boolean retryable) {
    }
}
