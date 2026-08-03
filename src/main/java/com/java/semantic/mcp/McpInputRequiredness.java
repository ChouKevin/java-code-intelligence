package com.java.semantic.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** 統一定義 MCP record 輸入的發布與執行 requiredness */
final class McpInputRequiredness {

    private McpInputRequiredness() {
        throw new UnsupportedOperationException("utility class");
    }

    static boolean isRequired(RecordComponent component) {
        return component.isAnnotationPresent(NotNull.class)
                || component.isAnnotationPresent(NotBlank.class)
                || component.isAnnotationPresent(NotEmpty.class)
                || component.getAnnotatedType().isAnnotationPresent(NotNull.class)
                || component.getAnnotatedType().isAnnotationPresent(NotBlank.class)
                || component.getAnnotatedType().isAnnotationPresent(NotEmpty.class)
                || component.getType().isPrimitive();
    }

    static void validate(Map<String, Object> arguments, Object input) {
        if (input instanceof Record record) {
            validateRecord(arguments, record);
        }
    }

    private static void validateRecord(Map<?, ?> arguments, Record input) {
        for (RecordComponent component : input.getClass().getRecordComponents()) {
            String name = component.getName();
            Object rawValue = arguments.get(name);
            if (isRequired(component) && (!arguments.containsKey(name) || rawValue == null)) { // cs-allow
                throw McpToolContractException.invalidToolInput(
                        new IllegalArgumentException("required input is missing: " + name));
            }
            validateNested(rawValue, componentValue(component, input));
        }
    }

    private static void validateNested(Object rawValue, Object inputValue) {
        if (inputValue instanceof Record record && rawValue instanceof Map<?, ?> arguments) {
            validateRecord(arguments, record);
            return;
        }
        if (inputValue instanceof Optional<?> optional) {
            optional.ifPresent(value -> validateNested(rawValue, value));
            return;
        }
        if (inputValue instanceof Iterable<?> inputValues && rawValue instanceof Iterable<?> rawValues) {
            Iterator<?> rawIterator = rawValues.iterator();
            for (Object inputElement : inputValues) {
                if (!rawIterator.hasNext()) {
                    return;
                }
                validateNested(rawIterator.next(), inputElement);
            }
        }
    }

    private static Object componentValue(RecordComponent component, Record input) {
        try {
            return component.getAccessor().invoke(input);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("cannot inspect MCP record input", exception);
        }
    }
}
