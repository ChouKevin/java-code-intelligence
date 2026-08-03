package com.java.semantic.mcp;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictMcpToolInputDecoderTest {

    private StrictMcpToolInputDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);
    }

    @Test
    void should_decode_a_typed_input_without_scalar_coercion() {
        SampleInput input = decoder.decode(Map.of("name", "orders", "limit", 5), SampleInput.class);

        assertThat(input).isEqualTo(new SampleInput("orders", 5));
    }

    @Test
    void should_reject_unknown_arguments_as_invalid_tool_input() {
        assertInvalidInput(Map.of("name", "orders", "limit", 5, "unexpected", "value"));
    }

    @Test
    void should_reject_scalar_coercion_as_invalid_tool_input() {
        assertInvalidInput(Map.of("name", "orders", "limit", "5"));
    }

    @Test
    void should_reject_missing_or_null_required_arguments_as_invalid_tool_input() {
        assertInvalidInput(Map.of("limit", 5));
        Map<String, Object> nullArguments = new HashMap<>();
        nullArguments.put("name", "orders");
        nullArguments.put("limit", null);
        assertInvalidInput(nullArguments);
    }

    @Test
    void should_reject_jakarta_constraint_violations_as_invalid_tool_input() {
        assertInvalidInput(Map.of("name", "", "limit", 21));
    }

    private void assertInvalidInput(Map<String, Object> arguments) {
        assertThatThrownBy(() -> decoder.decode(arguments, SampleInput.class))
                .isInstanceOf(McpToolContractException.class)
                .extracting(exception -> ((McpToolContractException) exception).code())
                .isEqualTo("INVALID_TOOL_INPUT");
    }

    private record SampleInput(@NotBlank String name, @NotNull @Min(1) @Max(20) Integer limit) {
    }
}
