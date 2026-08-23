package com.java.semantic.mcp;

import com.java.semantic.mcp.dto.McpRepositoryScopedInput;
import com.java.semantic.mcp.dto.McpRevisionPinnedInput;
import com.java.semantic.mcp.monitoring.McpInvocationMonitor;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 將已驗證的 MCP 查詢目錄顯式投影為無狀態 server 工具 */
@Configuration
@ConditionalOnBean(McpQueryProvider.class)
public class McpToolCatalogConfiguration {

    @Bean
    public McpQueryRegistry mcpQueryRegistry(List<McpQueryProvider> providers) {
        return new McpQueryRegistry(providers);
    }

    @Bean
    public StrictMcpToolInputDecoder strictMcpToolInputDecoder(ObjectMapper objectMapper, Validator validator) {
        return new StrictMcpToolInputDecoder(objectMapper, validator);
    }

    @Bean
    public McpQuerySchemaFactory mcpQuerySchemaFactory() {
        return new McpQuerySchemaFactory();
    }

    @Bean
    public McpInvocationMonitor mcpInvocationMonitor() {
        return new McpInvocationMonitor();
    }

    @Bean
    public JsonMapperBuilderCustomizer mcpErrorSerializationCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("mcp-error-sanitization");
            module.addSerializer(McpError.class, new McpErrorSerializer());
            builder.addModule(module);
        };
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> mcpQueryToolSpecifications(
            McpQueryRegistry registry,
            StrictMcpToolInputDecoder inputDecoder,
            McpQuerySchemaFactory schemaFactory,
            McpInvocationMonitor invocationMonitor,
            ObjectMapper objectMapper) {
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = registry.registrations().stream()
                .map(registration -> toolSpecification(
                        registration, inputDecoder, schemaFactory, invocationMonitor, objectMapper))
                .toList();
        validatePublishedSpecifications(specifications);
        return specifications;
    }

    private static McpStatelessServerFeatures.SyncToolSpecification toolSpecification(
            McpQueryRegistration<?, ?> registration,
            StrictMcpToolInputDecoder inputDecoder,
            McpQuerySchemaFactory schemaFactory,
            McpInvocationMonitor invocationMonitor,
            ObjectMapper objectMapper) {
        String inputSchema = schemaFactory.generateInputSchema(registration.inputType());
        String outputSchema = schemaFactory.generateOutputSchema(registration.outputType());
        McpSchema.Tool tool = McpSchema.Tool.builder(registration.name())
                .description(registration.description())
                .inputSchema(schemaMap(objectMapper, inputSchema))
                .outputSchema(schemaMap(objectMapper, outputSchema))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .build())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> invoke(
                        registration, inputDecoder, invocationMonitor, objectMapper, request.arguments()))
                .build();
    }

    private static Map<String, Object> schemaMap(ObjectMapper objectMapper, String schema) {
        Assert.notNull(objectMapper, "objectMapper is required");
        Assert.hasText(schema, "schema is required");
        try {
            return objectMapper.readValue(schema, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException("generated MCP schema is invalid", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <I, O> McpSchema.CallToolResult invoke(
            McpQueryRegistration<?, ?> registration,
            StrictMcpToolInputDecoder inputDecoder,
            McpInvocationMonitor invocationMonitor,
            ObjectMapper objectMapper,
            Map<String, Object> arguments) {
        McpQueryRegistration<I, O> typedRegistration = (McpQueryRegistration<I, O>) registration;
        AtomicReference<I> decodedInput = new AtomicReference<>();
        try {
            return invocationMonitor.monitor(typedRegistration.name(), () -> {
                I input = inputDecoder.decode(arguments, typedRegistration.inputType());
                decodedInput.set(input);
                Function<I, O> handler = typedRegistration.handler();
                O output = handler.apply(input);
                McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                        .addTextContent("Query completed")
                        .structuredContent(output)
                        .isError(false)
                        .build();
                return new McpInvocationMonitor.MonitoredInvocation<>(input, output, result);
            });
        } catch (RuntimeException exception) {
            McpToolFailure.RequestIdentity requestIdentity = requestIdentity(decodedInput.get());
            Optional<McpToolFailure> knownFailure = McpToolFailureMapper.failureFor(exception, requestIdentity);
            McpToolFailure failure = knownFailure.orElseGet(
                    () -> McpToolFailureMapper.internalFailure(requestIdentity));
            return failureResult(failure, objectMapper);
        }
    }

    private static McpSchema.CallToolResult failureResult(McpToolFailure failure, ObjectMapper objectMapper) {
        try {
            String failureJson = objectMapper.writeValueAsString(failure);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(failureJson)
                    .isError(true)
                    .build();
        } catch (JacksonException exception) {
            throw new IllegalStateException("MCP tool failure serialization failed", exception);
        }
    }

    private static McpToolFailure.RequestIdentity requestIdentity(Object input) {
        if (input instanceof McpRevisionPinnedInput revisionPinnedInput) {
            return new McpToolFailure.RequestIdentity(
                    revisionPinnedInput.repoId(), revisionPinnedInput.expectedRevision(), null);
        }
        if (input instanceof McpRepositoryScopedInput repositoryScopedInput) {
            return new McpToolFailure.RequestIdentity(repositoryScopedInput.repoId(), null, null);
        }
        return null;
    }

    private static void validatePublishedSpecifications(
            List<McpStatelessServerFeatures.SyncToolSpecification> specifications) {
        List<String> names = specifications.stream()
                .map(specification -> specification.tool().name())
                .sorted()
                .toList();
        if (!names.equals(McpQueryRegistry.canonicalToolNames())) {
            throw new IllegalStateException("published MCP tool catalog differs from the canonical allowlist");
        }
    }

    /** 將 MCP 傳輸錯誤限制為其 JSON-RPC 契約資料，避免例外細節離開傳輸邊界 */
    private static final class McpErrorSerializer extends ValueSerializer<McpError> {

        @Override
        public void serialize(McpError value, JsonGenerator generator, SerializationContext context)
                throws JacksonException {
            McpSchema.JSONRPCResponse.JSONRPCError error = Objects.requireNonNull(
                    value.getJsonRpcError(), "jsonRpcError is required");
            generator.writeStartObject();
            generator.writeName("jsonRpcError");
            generator.writeStartObject();
            generator.writeNumberProperty("code", error.code());
            generator.writeStringProperty("message", safeMessage(error.code()));
            generator.writeEndObject();
            generator.writeEndObject();
        }

        private String safeMessage(int code) {
            return switch (code) {
                case -32700 -> "Parse error";
                case -32600 -> "Invalid Request";
                case -32601 -> "Method not found";
                case -32602 -> "Invalid params";
                case -32603 -> "Internal error";
                default -> "Request failed";
            };
        }
    }
}
