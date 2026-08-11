package com.java.semantic.mcp;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import org.springframework.util.Assert;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/** 以 VicTools 投影 Jakarta validation 與 Jackson 契約為 MCP 工具 schema。 */
public final class McpQuerySchemaFactory {

    private static final JsonMapper SCHEMA_MAPPER = new JsonMapper();
    private static final TypeReference<Map<String, Object>> SCHEMA_MAP_TYPE = new TypeReference<>() {
    };

    private final SchemaGenerator inputSchemaGenerator;
    private final SchemaGenerator outputSchemaGenerator;

    public McpQuerySchemaFactory() {
        inputSchemaGenerator = new SchemaGenerator(schemaConfig(false));
        outputSchemaGenerator = new SchemaGenerator(schemaConfig(true));
    }

    public Map<String, Object> generateInputSchema(Class<?> type) {
        return generate(type, inputSchemaGenerator);
    }

    public Map<String, Object> generateOutputSchema(Class<?> type) {
        return generate(type, outputSchemaGenerator);
    }

    private static SchemaGeneratorConfig schemaConfig(boolean output) {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonSchemaModule(
                        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.RESPECT_JSONPROPERTY_ORDER))
                .with(new PortablePatternJakartaValidationModule())
                .with(
                        Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
                        Option.PLAIN_DEFINITION_KEYS);
        if (output) {
            builder.with(new OutputPropertiesRequiredModule());
        }
        return builder.build();
    }

    private static Map<String, Object> generate(Class<?> type, SchemaGenerator schemaGenerator) {
        Assert.notNull(type, "type is required");
        Assert.notNull(schemaGenerator, "schemaGenerator is required");
        try {
            ObjectNode schema;
            synchronized (schemaGenerator) {
                schema = schemaGenerator.generateSchema(type);
            }
            Map<String, Object> generatedSchema = SCHEMA_MAPPER.treeToValue(schema, SCHEMA_MAP_TYPE);
            return Map.copyOf(generatedSchema);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("generated MCP schema is invalid", exception);
        }
    }

    static boolean isPortableMcpPattern(String pattern) {
        String candidate = Objects.requireNonNull(pattern, "pattern is required");
        return !candidate.contains("\\p{")
                && !candidate.contains("\\P{")
                && !candidate.contains("\\A")
                && !candidate.contains("\\z")
                && !candidate.contains("\\Z")
                && !candidate.contains("\\h")
                && !candidate.contains("\\H")
                && !candidate.contains("\\R")
                && !candidate.contains("(?")
                && !hasPossessiveQuantifier(candidate);
    }

    private static boolean hasPossessiveQuantifier(String pattern) {
        for (int index = 1; index < pattern.length(); index++) {
            if (pattern.charAt(index) == '+' && "*+?}".indexOf(pattern.charAt(index - 1)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static final class PortablePatternJakartaValidationModule extends JakartaValidationModule {

        private PortablePatternJakartaValidationModule() {
            super(
                    JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                    JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                    JakartaValidationOption.NOT_NULLABLE_METHOD_IS_REQUIRED);
        }

        @Override
        protected String resolveStringPattern(MemberScope<?, ?> member) {
            String pattern = super.resolveStringPattern(member);
            return Objects.nonNull(pattern) && isPortableMcpPattern(pattern) ? pattern : null;
        }

        @Override
        protected boolean isRequired(MemberScope<?, ?> member) {
            return super.isRequired(member)
                    || (!member.isFakeContainerItemScope() && member.getType().isPrimitive());
        }
    }

    private static final class OutputPropertiesRequiredModule implements Module {

        @Override
        public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
            builder.forFields().withRequiredCheck(member -> !member.isFakeContainerItemScope());
            builder.forMethods().withRequiredCheck(member -> !member.isFakeContainerItemScope());
        }
    }
}
