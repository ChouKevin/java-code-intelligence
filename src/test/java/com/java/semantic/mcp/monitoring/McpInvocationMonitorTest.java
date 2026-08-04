package com.java.semantic.mcp.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.mcp.McpToolContractException;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證 MCP 工具監控只記錄明確允許的 typed projection */
class McpInvocationMonitorTest {

    @Test
    void should_project_annotated_request_and_response_without_source_content() {
        CapturedLogs capturedLogs = captureLogs();
        MDC.put("requestId", "request-42");
        try {
            String result = new McpInvocationMonitor().monitor("semantic_get_source_segment", () ->
                    new McpInvocationMonitor.MonitoredInvocation<>(
                            new SourceRequest("repo-42", "private-request-body"),
                            new SourceResponse("repo-42", "private-source-content"),
                            "result"));

            assertThat(result).isEqualTo("result");
            assertThat(capturedLogs.messages())
                    .anyMatch(message -> message.contains("segment=request.repoId=repo-42"))
                    .anyMatch(message -> message.contains("segment=response.repoId=repo-42"))
                    .anyMatch(message -> message.startsWith(
                            "mcp_tool_completed requestId=request-42 toolName=semantic_get_source_segment resultCategory=SUCCESS"))
                    .noneMatch(message -> message.contains("private-request-body"))
                    .noneMatch(message -> message.contains("private-source-content"));
        } finally {
            MDC.remove("requestId");
            capturedLogs.stop();
        }
    }

    @Test
    void should_preserve_typed_invalid_input_failure_without_logging_raw_input() {
        CapturedLogs capturedLogs = captureLogs();
        try {
            assertThatThrownBy(() -> new McpInvocationMonitor().monitor("semantic_get_repository", () -> {
                throw McpToolContractException.invalidToolInput(new IllegalArgumentException("private-invalid-input"));
            }))
                    .isInstanceOf(McpToolContractException.class)
                    .extracting(exception -> ((McpToolContractException) exception).code())
                    .isEqualTo("INVALID_TOOL_INPUT");

            assertThat(capturedLogs.messages())
                    .anyMatch(message -> message.startsWith(
                            "mcp_tool_completed requestId= toolName=semantic_get_repository resultCategory=INVALID_TOOL_INPUT"))
                    .noneMatch(message -> message.contains("private-invalid-input"));
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_classify_known_query_failure_without_logging_exception_details() {
        CapturedLogs capturedLogs = captureLogs();
        try {
            assertThatThrownBy(() -> new McpInvocationMonitor().monitor("semantic_get_repository", () -> {
                throw new RepositoryNotReadyException(RepositoryId.of("orders"));
            })).isInstanceOf(RepositoryNotReadyException.class);

            assertThat(capturedLogs.messages())
                    .anyMatch(message -> message.startsWith(
                            "mcp_tool_completed requestId= toolName=semantic_get_repository resultCategory=EXPECTED_TOOL_FAILURE"))
                    .noneMatch(message -> message.contains("orders"));
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_omit_call_graph_call_expression_from_monitoring_segments() {
        CapturedLogs capturedLogs = captureLogs();
        try {
            CallGraphMcpDtos.GraphEdgeOutput edge = new CallGraphMcpDtos.GraphEdgeOutput(
                    null, null, null, "secret-token", null, List.of(), List.of());
            CallGraphMcpDtos.OutgoingOutput response = new CallGraphMcpDtos.OutgoingOutput(
                    new CallGraphMcpDtos.GraphResult(
                            null, null, null, null, List.of(), List.of(edge), List.of(), List.of()));

            new McpInvocationMonitor().monitor("semantic_analyze_outgoing_call_graph", () ->
                    new McpInvocationMonitor.MonitoredInvocation<>(
                            new SourceRequest("orders", "private-input"), response, "result"));

            assertThat(capturedLogs.messages())
                    .anyMatch(message -> message.contains("segment=request.repoId=orders"))
                    .noneMatch(message -> message.contains("secret-token"));
        } finally {
            capturedLogs.stop();
        }
    }

    private CapturedLogs captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpInvocationMonitor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private record SourceRequest(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.OMIT) String requestBody) {
    }

    private record SourceResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.OMIT) String content) {
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) {

        List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        void stop() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
