package com.java.semantic.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

/** 以 Jakarta requiredness 補正 Spring AI MCP 工具 schema */
public final class McpQuerySchemaFactory {

    public String generateForType(Type type) {
        Assert.notNull(type, "type is required");
        String generatedSchema = JsonSchemaGenerator.generateForType(type);
        if (!(type instanceof Class<?> inputType) || !inputType.isRecord()) {
            return generatedSchema;
        }
        return applyRecordRequiredness(generatedSchema, inputType);
    }

    private String applyRecordRequiredness(String generatedSchema, Class<?> recordType) {
        try {
            JsonMapper objectMapper = new JsonMapper();
            JsonNode schema = objectMapper.readTree(generatedSchema);
            ObjectNode objectSchema = (ObjectNode) schema;
            ArrayNode required = objectSchema.withArray("required");
            required.removeAll();
            for (RecordComponent component : recordType.getRecordComponents()) {
                if (isRequired(component)) {
                    required.add(component.getName());
                }
            }
            return objectMapper.writeValueAsString(objectSchema);
        } catch (JacksonException exception) {
            throw new IllegalStateException("generated MCP schema is invalid", exception);
        }
    }

    private boolean isRequired(RecordComponent component) {
        return component.isAnnotationPresent(NotNull.class)
                || component.isAnnotationPresent(NotBlank.class)
                || component.isAnnotationPresent(NotEmpty.class)
                || component.getAnnotatedType().isAnnotationPresent(NotNull.class)
                || component.getAnnotatedType().isAnnotationPresent(NotBlank.class)
                || component.getAnnotatedType().isAnnotationPresent(NotEmpty.class)
                || component.getType().isPrimitive();
    }
}
