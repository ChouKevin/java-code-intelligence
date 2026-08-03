package com.java.semantic.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
    public List<McpStatelessServerFeatures.SyncToolSpecification> mcpQueryToolSpecifications(
            McpQueryRegistry registry,
            StrictMcpToolInputDecoder inputDecoder,
            McpQuerySchemaFactory schemaFactory,
            ObjectMapper objectMapper) {
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = registry.registrations().stream()
                .map(registration -> toolSpecification(registration, inputDecoder, schemaFactory, objectMapper))
                .toList();
        validatePublishedSpecifications(specifications);
        return specifications;
    }

    private static McpStatelessServerFeatures.SyncToolSpecification toolSpecification(
            McpQueryRegistration<?, ?> registration,
            StrictMcpToolInputDecoder inputDecoder,
            McpQuerySchemaFactory schemaFactory,
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
                .callHandler((context, request) -> invoke(registration, inputDecoder, request.arguments()))
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
            Map<String, Object> arguments) {
        McpQueryRegistration<I, O> typedRegistration = (McpQueryRegistration<I, O>) registration;
        I input = inputDecoder.decode(arguments, typedRegistration.inputType());
        Function<I, O> handler = typedRegistration.handler();
        O output = handler.apply(input);
        return McpSchema.CallToolResult.builder()
                .structuredContent(output)
                .build();
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
}
