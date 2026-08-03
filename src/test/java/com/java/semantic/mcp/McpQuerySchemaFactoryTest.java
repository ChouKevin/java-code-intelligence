package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.route.ApiRouteMcpDtos;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpQuerySchemaFactoryTest {

    private final McpQuerySchemaFactory schemaFactory = new McpQuerySchemaFactory();
    private final JsonMapper objectMapper = new JsonMapper();

    @Test
    void should_generate_closed_nested_object_schemas_with_required_and_constraint_metadata() throws Exception {
        JsonNode schema = objectMapper.readTree(schemaFactory.generateForType(RepresentativeInput.class));

        assertThat(schema.at("/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/required").toString()).contains("name", "label", "mode", "limit", "children");
        assertThat(schema.at("/properties/name/minLength").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/name/pattern").asText()).isEqualTo(".*\\S.*");
        assertThat(schema.at("/properties/mode/enum").toString()).contains("FAST", "SAFE");
        assertThat(schema.at("/properties/limit/minimum").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/limit/maximum").asInt()).isEqualTo(20);
        assertThat(schema.at("/properties/children/minItems").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/children/maxItems").asInt()).isEqualTo(3);
        assertThat(schema.at("/properties/code/pattern").asText()).isEqualTo("[A-Z]+");
        assertThat(schema.at("/properties/label/pattern").asText()).isEqualTo("[A-Z]+");
        assertThat(schema.at("/properties/label/allOf/0/pattern").asText()).isEqualTo(".*\\S.*");
        assertThat(schema.at("/properties/offset/minimum").asInt()).isZero();
        assertThat(schema.at("/properties/children/items/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void should_generate_polymorphic_output_schema() throws Exception {
        JsonNode schema = objectMapper.readTree(schemaFactory.generateForType(RepresentativeOutput.class));

        assertThat(schema.at("/properties/result/anyOf").isArray()).isTrue();
    }

    @Test
    void should_share_optional_and_required_input_contracts_with_the_decoder() throws Exception {
        JsonNode lookupSchema = objectMapper.readTree(schemaFactory.generateForType(ApiRouteMcpDtos.LookupInput.class));
        JsonNode listenersSchema = objectMapper.readTree(
                schemaFactory.generateForType(FrameworkDiscoveryMcpDtos.EventListenersInput.class));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StrictMcpToolInputDecoder decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);

        assertThat(lookupSchema.at("/required").toString()).contains("repoId", "expectedRevision", "apiPath")
                .doesNotContain("httpMethod");
        assertThat(listenersSchema.at("/required").toString()).contains(
                "repoId", "expectedRevision", "eventType", "limit").doesNotContain("offset");
        assertThat(decoder.decode(
                Map.of("repoId", "orders", "expectedRevision", "FIXTURE", "apiPath", "/orders"),
                ApiRouteMcpDtos.LookupInput.class).httpMethod()).isNull();
        assertThat(decoder.decode(
                Map.of("repoId", "orders", "expectedRevision", "FIXTURE", "eventType", "created", "limit", 5),
                FrameworkDiscoveryMcpDtos.EventListenersInput.class).offset()).isNull();
        assertThatThrownBy(() -> decoder.decode(
                Map.of("repoId", "orders", "expectedRevision", "FIXTURE", "apiPath", "/orders"),
                ApiRouteMcpDtos.SuggestInput.class))
                .isInstanceOf(McpToolContractException.class);
    }

    private record RepresentativeInput(
            @NotBlank String name,
            @NotNull Mode mode,
            @NotNull @Min(1) @Max(20) Integer limit,
            @NotEmpty @Size(min = 1, max = 3) List<Child> children,
            @Pattern(regexp = "[A-Z]+") String code,
            @NotBlank @Pattern(regexp = "[A-Z]+") String label,
            @PositiveOrZero int offset) {
    }

    private record Child(@NotBlank String value) {
    }

    private record RepresentativeOutput(@NotNull Result result) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextResult.class, name = "text"),
            @JsonSubTypes.Type(value = NumericResult.class, name = "numeric")
    })
    private sealed interface Result permits TextResult, NumericResult {
    }

    private record TextResult(@NotBlank String value) implements Result {
    }

    private record NumericResult(@Min(0) int value) implements Result {
    }

    private enum Mode {
        FAST,
        SAFE
    }
}
