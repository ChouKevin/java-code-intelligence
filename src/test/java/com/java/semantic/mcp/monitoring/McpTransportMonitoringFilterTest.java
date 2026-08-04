package com.java.semantic.mcp.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.api.RequestCorrelationFilter;
import com.java.semantic.api.security.ApiSecurityProperties;
import com.java.semantic.api.security.ApiTokenFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 MCP transport rejection 監控不會讀取 JSON-RPC body */
class McpTransportMonitoringFilterTest {

    @Test
    void should_monitor_unauthorized_mcp_rejection_without_logging_raw_payload() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
            request.setAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE, "request-42");
            request.setContent("{\"privateSourceBody\":\"never-log-me\"}".getBytes());
            MockHttpServletResponse response = new MockHttpServletResponse();
            ApiSecurityProperties properties = new ApiSecurityProperties();
            properties.setApiToken("configured");
            ApiTokenFilter tokenFilter = new ApiTokenFilter(properties, new ObjectMapper());

            new McpTransportMonitoringFilter("/mcp").doFilter(request, response, (chainedRequest, chainedResponse) ->
                    tokenFilter.doFilter(chainedRequest, chainedResponse, new MockFilterChain()));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(capturedLogs.messages())
                    .anyMatch(message -> message.startsWith(
                            "mcp_transport_completed requestId=request-42 method=POST requestPath=/mcp observedStatus=401 outcome=COMPLETED errorCode=SEMANTIC_UNAUTHORIZED"))
                    .noneMatch(message -> message.contains("never-log-me"));
        } finally {
            capturedLogs.stop();
        }
    }

    private CapturedLogs captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpTransportMonitoringFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
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
