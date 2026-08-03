package com.java.semantic.mcp;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
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
        ObjectMapper objectMapper = new JsonMapper().rebuild()
                .disable(
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .build();
        decoder = new StrictMcpToolInputDecoder(objectMapper, validator);
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
    void should_reject_missing_or_null_required_nested_primitive_arguments_as_invalid_tool_input() {
        assertInvalidInput(Map.of("coordinates", Map.of("character", 4)), NestedPrimitiveInput.class);

        Map<String, Object> coordinates = new HashMap<>();
        coordinates.put("line", null);
        coordinates.put("character", 4);

        assertInvalidInput(Map.of("coordinates", coordinates), NestedPrimitiveInput.class);
    }

    @Test
    void should_accept_missing_or_null_optional_arguments() {
        OptionalInput omitted = decoder.decode(Map.of("name", "orders", "limit", 5), OptionalInput.class);
        Map<String, Object> nullArguments = new HashMap<>();
        nullArguments.put("name", "orders");
        nullArguments.put("limit", 5);
        nullArguments.put("filter", null);

        OptionalInput nullValue = decoder.decode(nullArguments, OptionalInput.class);

        assertThat(omitted.filter()).isNull();
        assertThat(nullValue.filter()).isNull();
    }

    @Test
    void should_reject_jakarta_constraint_violations_as_invalid_tool_input() {
        assertInvalidInput(Map.of("name", "", "limit", 21));
    }

    private <I> void assertInvalidInput(Map<String, Object> arguments, Class<I> inputType) {
        assertThatThrownBy(() -> decoder.decode(arguments, inputType))
                .isInstanceOf(McpToolContractException.class)
                .extracting(exception -> ((McpToolContractException) exception).code())
                .isEqualTo("INVALID_TOOL_INPUT");
    }

    private void assertInvalidInput(Map<String, Object> arguments) {
        assertInvalidInput(arguments, SampleInput.class);
    }

    private record SampleInput(@NotBlank String name, @NotNull @Min(1) @Max(20) Integer limit) {
    }

    private record OptionalInput(
            @NotBlank String name,
            @NotNull @Min(1) @Max(20) Integer limit,
            String filter) {
    }

    private record NestedPrimitiveInput(@NotNull @Valid Coordinates coordinates) {
    }

    private record Coordinates(int line, int character) {
    }
}
