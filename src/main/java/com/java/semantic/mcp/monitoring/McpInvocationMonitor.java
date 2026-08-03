package com.java.semantic.mcp.monitoring;

import com.java.semantic.mcp.McpToolContractException;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.monitoring.MonitoringProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 在 MCP 註冊邊界投影工具呼叫的安全監控欄位 */
public final class McpInvocationMonitor {

    private static final Logger log = LoggerFactory.getLogger(McpInvocationMonitor.class);
    private static final int MAX_VALUE_LENGTH = 1_024;

    public <T> T monitor(String toolName, Supplier<MonitoredInvocation<T>> invocation) {
        Assert.hasText(toolName, "toolName is required");
        Supplier<MonitoredInvocation<T>> monitoredInvocation =
                Objects.requireNonNull(invocation, "invocation is required");
        long startedAt = System.nanoTime();
        ResultCategory resultCategory = ResultCategory.SUCCESS;
        try {
            MonitoredInvocation<T> result = monitoredInvocation.get();
            emitProjection("request", result.request());
            emitProjection("response", result.response());
            return result.result();
        } catch (McpToolContractException exception) {
            resultCategory = ResultCategory.INVALID_TOOL_INPUT;
            throw exception;
        } catch (RuntimeException exception) {
            resultCategory = ResultCategory.APPLICATION_FAILURE;
            throw exception;
        } catch (Error error) {
            resultCategory = ResultCategory.APPLICATION_FAILURE;
            throw error;
        } finally {
            log.info(
                    "mcp_tool_completed requestId={} toolName={} resultCategory={} durationMillis={}",
                    requestId(),
                    toolName,
                    resultCategory,
                    elapsedMillis(startedAt));
        }
    }

    private void emitProjection(String root, Object value) {
        try {
            for (String segment : new ProjectionRenderer().render(root, value)) {
                log.info("mcp_tool_monitoring_segment requestId={} segment={}", requestId(), segment);
            }
        } catch (RuntimeException exception) {
            log.error("mcp_tool_monitoring_contract_defect exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private String requestId() {
        return Objects.toString(MDC.get("requestId"), "");
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** 保存已執行工具的 typed input、output 與原始結果 */
    public record MonitoredInvocation<T>(Object request, Object response, T result) {
    }

    private enum ResultCategory {
        SUCCESS,
        INVALID_TOOL_INPUT,
        APPLICATION_FAILURE
    }

    private static final class ProjectionRenderer {

        private final List<String> segments = new ArrayList<>();

        List<String> render(String root, Object value) {
            Assert.hasText(root, "root is required");
            if (Objects.nonNull(value)) {
                renderRecord(root, value);
            }
            return List.copyOf(segments);
        }

        private void renderRecord(String root, Object value) {
            if (!MonitoringProjection.hasMonitoringFields(value)) {
                return;
            }
            for (MonitoringProjection.MonitoringComponent component : MonitoringProjection.project(value)) {
                renderComponent(root + "." + component.name(), component.mode(), component.value());
            }
        }

        private void renderComponent(String fieldName, MonitoringMode mode, Object value) {
            if (Objects.isNull(value) || mode == MonitoringMode.OMIT) {
                return;
            }
            switch (mode) {
                case VALUE -> renderValue(fieldName, value);
                case SIZE -> renderSize(fieldName, value);
                case NESTED -> renderNested(fieldName, value);
                case OMIT -> { }
            }
        }

        private void renderValue(String fieldName, Object value) {
            if (value instanceof Optional<?> optionalValue) {
                optionalValue.ifPresent(item -> renderValue(fieldName, item));
                return;
            }
            if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                    || value instanceof Enum<?>) {
                segments.add(fieldName + "=" + safeValue(value.toString()));
            }
        }

        private void renderSize(String fieldName, Object value) {
            if (value instanceof CharSequence sequence) {
                segments.add(fieldName + ".size=" + sequence.length());
                return;
            }
            if (value instanceof Collection<?> collection) {
                segments.add(fieldName + ".size=" + collection.size());
            }
        }

        private void renderNested(String fieldName, Object value) {
            if (value instanceof Optional<?> optionalValue) {
                optionalValue.ifPresent(item -> renderNested(fieldName, item));
                return;
            }
            if (value instanceof List<?> nestedValues) {
                for (int index = 0; index < nestedValues.size(); index++) {
                    renderRecord(fieldName + "[" + index + "]", nestedValues.get(index));
                }
                return;
            }
            renderRecord(fieldName, value);
        }

        private String safeValue(String value) {
            StringBuilder rendered = new StringBuilder();
            for (int index = 0; index < value.length() && rendered.length() < MAX_VALUE_LENGTH;) {
                int codePoint = value.codePointAt(index);
                if (Character.isISOControl(codePoint)) {
                    rendered.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
                } else {
                    rendered.appendCodePoint(codePoint);
                }
                index += Character.charCount(codePoint);
            }
            return rendered.toString();
        }
    }
}
