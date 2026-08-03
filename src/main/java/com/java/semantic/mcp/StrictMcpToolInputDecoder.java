package com.java.semantic.mcp;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.util.Assert;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.core.JacksonException;

import java.util.Map;
import java.util.Set;

/** 將 MCP SDK 已解析的引數嚴格轉為受驗證的工具輸入 */
public final class StrictMcpToolInputDecoder {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public StrictMcpToolInputDecoder(ObjectMapper objectMapper, Validator validator) {
        Assert.notNull(objectMapper, "objectMapper is required");
        Assert.notNull(validator, "validator is required");
        this.objectMapper = objectMapper.rebuild()
                .enable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .withCoercionConfigDefaults(configuration -> {
                    configuration.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    configuration.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    configuration.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    configuration.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                })
                .build();
        this.validator = validator;
    }

    public <I> I decode(Map<String, Object> arguments, Class<I> inputType) {
        Assert.notNull(arguments, "arguments are required");
        Assert.notNull(inputType, "inputType is required");
        try {
            JsonNode argumentTree = objectMapper.valueToTree(arguments);
            I input = objectMapper.treeToValue(argumentTree, inputType);
            McpInputRequiredness.validate(arguments, input);
            Set<ConstraintViolation<I>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                throw McpToolContractException.invalidToolInput(new IllegalArgumentException("input validation failed"));
            }
            return input;
        } catch (McpToolContractException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw McpToolContractException.invalidToolInput(exception);
        }
    }
}
