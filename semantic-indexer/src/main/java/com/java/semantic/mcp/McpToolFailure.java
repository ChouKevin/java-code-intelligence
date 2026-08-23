package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.util.Assert;

import java.util.List;

/** MCP 工具已知失敗的安全結構化回應 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolFailure(
        String errorCode,
        String message,
        RequestIdentity requestIdentity,
        Recovery recovery) {

    public McpToolFailure {
        Assert.hasText(errorCode, "errorCode is required");
        Assert.hasText(message, "message is required");
        Assert.notNull(recovery, "recovery is required");
    }

    /** 保存可安全回傳給 caller 的 repository request identity */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestIdentity(String repoId, String expectedRevision, String currentRevision) {

        public RequestIdentity {
            Assert.hasText(repoId, "repoId is required");
        }
    }

    /** MCP client 可安全據以決定是否等待後重試的資訊 */
    public record Recovery(boolean retryable, List<AvailableFollowUp> availableFollowUps) {

        public Recovery {
            Assert.notNull(availableFollowUps, "availableFollowUps is required");
            availableFollowUps = List.copyOf(availableFollowUps);
        }
    }

    /** 指向可直接呼叫 MCP tool 的 typed recovery request */
    public record AvailableFollowUp(String toolName, Object arguments) {

        public AvailableFollowUp {
            Assert.hasText(toolName, "toolName is required");
            Assert.notNull(arguments, "arguments are required");
        }
    }
}
