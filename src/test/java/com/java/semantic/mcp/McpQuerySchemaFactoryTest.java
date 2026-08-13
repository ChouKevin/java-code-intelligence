package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpQuerySchemaFactoryTest {

    private final McpQuerySchemaFactory schemaFactory = new McpQuerySchemaFactory();
    private final JsonMapper objectMapper = new JsonMapper();

    @Test
    void should_generate_closed_nested_object_schemas_with_required_and_constraint_metadata() throws Exception {
        JsonNode schema = schema(schemaFactory.generateInputSchema(RepresentativeInput.class));

        assertThat(schema.at("/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/required")).extracting(JsonNode::asText)
                .contains("name", "label", "mode", "limit", "children");
        assertThat(schema.at("/properties/name/minLength").asInt()).isEqualTo(1);
        assertThat(schema.toString()).doesNotContain(".*\\S.*");
        assertThat(schema.at("/properties/mode/enum").toString()).contains("FAST", "SAFE");
        assertThat(schema.at("/properties/limit/minimum").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/limit/maximum").asInt()).isEqualTo(20);
        assertThat(schema.at("/properties/children/minItems").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/children/maxItems").asInt()).isEqualTo(3);
        assertThat(schema.at("/properties/code/pattern").asText()).isEqualTo("[A-Z]+");
        assertThat(schema.at("/properties/label/pattern").asText()).isEqualTo("[A-Z]+");
        assertThat(schema.at("/properties/offset/minimum").asInt()).isZero();
        assertThat(schema.at("/properties/children/items/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void should_generate_polymorphic_output_schema() throws Exception {
        JsonNode schema = schema(schemaFactory.generateOutputSchema(RepresentativeOutput.class));

        assertThat(schema.at("/properties/result/anyOf").isArray()).isTrue();
        assertThat(schema.at("/required")).extracting(JsonNode::asText).contains("result");
        assertThat(schema.at("/properties/result/anyOf/0/required")).extracting(JsonNode::asText).contains("value");
        assertThat(allObjectPropertyOwnersHaveRequiredProperties(schema)).isTrue();
    }

    @Test
    void should_generate_polymorphic_output_schema_for_unannotated_sealed_domain_types() throws Exception {
        JsonNode schema = schema(schemaFactory.generateOutputSchema(DomainOutput.class));
        JsonNode branches = schema.at("/properties/result/anyOf");

        assertThat(branches).hasSize(2);
        assertThat(branches.toString()).contains("text", "number");
    }

    @Test
    void should_omit_java_specific_patterns_while_retaining_portable_constraints() throws Exception {
        JsonNode identitySchema = schema(schemaFactory.generateInputSchema(McpJavaIdentityPayloads.Method.class));
        JsonNode routeSchema = schema(schemaFactory.generateInputSchema(ApiRouteMcpDtos.LookupInput.class));

        assertThat(identitySchema.toString()).doesNotContain("javaJavaIdentifier", "javaWhitespace", "\\\\p{");
        assertThat(routeSchema.at("/properties/repoId/pattern").asText())
                .isEqualTo("^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(routeSchema.at("/properties/expectedRevision/pattern").asText())
                .isEqualTo("^[0-9a-f]{40}$|^FIXTURE$");
    }

    @Test
    void should_share_optional_and_required_input_contracts_with_the_decoder() throws Exception {
        JsonNode lookupSchema = schema(schemaFactory.generateInputSchema(ApiRouteMcpDtos.LookupInput.class));
        JsonNode listenersSchema = schema(schemaFactory.generateInputSchema(
                FrameworkDiscoveryMcpDtos.EventListenersInput.class));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StrictMcpToolInputDecoder decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);

        assertThat(lookupSchema.at("/required")).extracting(JsonNode::asText)
                .contains("repoId", "expectedRevision", "apiPath")
                .doesNotContain("httpMethod");
        assertThat(listenersSchema.at("/required")).extracting(JsonNode::asText)
                .contains("repoId", "expectedRevision", "eventType", "limit")
                .doesNotContain("offset");
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

    private JsonNode schema(Map<String, Object> generatedSchema) {
        return objectMapper.valueToTree(generatedSchema);
    }

    private boolean allObjectPropertyOwnersHaveRequiredProperties(JsonNode schema) {
        List<JsonNode> propertyOwners = new ArrayList<>();
        collectObjectPropertyOwners(schema, propertyOwners);
        return propertyOwners.stream().allMatch(owner -> owner.path("properties").propertyStream()
                .allMatch(property -> owner.path("required").valueStream()
                        .anyMatch(required -> required.asText().equals(property.getKey()))));
    }

    private void collectObjectPropertyOwners(JsonNode schema, List<JsonNode> propertyOwners) {
        if (schema.path("properties").isObject()) {
            propertyOwners.add(schema);
        }
        for (JsonNode child : schema) {
            collectObjectPropertyOwners(child, propertyOwners);
        }
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

    private record RepresentativeOutput(Result result) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextResult.class, name = "text"),
            @JsonSubTypes.Type(value = NumericResult.class, name = "numeric")
    })
    private sealed interface Result permits TextResult, NumericResult {
    }

    private record TextResult(String value) implements Result {
    }

    private record NumericResult(@Min(0) int value) implements Result {
    }

    private record DomainOutput(DomainResult result) {
    }

    private sealed interface DomainResult permits DomainTextResult, DomainNumericResult {
    }

    private record DomainTextResult(String text) implements DomainResult {
    }

    private record DomainNumericResult(@Min(0) int number) implements DomainResult {
    }

    private enum Mode {
        FAST,
        SAFE
    }
}
