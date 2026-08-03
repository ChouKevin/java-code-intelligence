package com.java.semantic.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import com.java.semantic.mcp.monitoring.McpInvocationMonitor;
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
        String inputSchema = schemaFactory.generateForType(registration.inputType());
        String outputSchema = schemaFactory.generateForType(registration.outputType());
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
                        registration, inputDecoder, invocationMonitor, request.arguments()))
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
            Map<String, Object> arguments) {
        McpQueryRegistration<I, O> typedRegistration = (McpQueryRegistration<I, O>) registration;
        return invocationMonitor.monitor(typedRegistration.name(), () -> {
            I input = inputDecoder.decode(arguments, typedRegistration.inputType());
            Function<I, O> handler = typedRegistration.handler();
            O output = handler.apply(input);
            McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                    .structuredContent(output)
                    .build();
            return new McpInvocationMonitor.MonitoredInvocation<>(input, output, result);
        });
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
            generator.writeStartObject();
            generator.writePOJOProperty("jsonRpcError", value.getJsonRpcError());
            generator.writeEndObject();
        }
    }
}
