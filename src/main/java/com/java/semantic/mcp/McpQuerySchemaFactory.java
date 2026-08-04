package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 以 Jakarta validation 契約補正 Spring AI MCP 工具 schema */
public final class McpQuerySchemaFactory {

    public String generateInputSchema(Type type) {
        return generate(type, SchemaPurpose.INPUT);
    }

    public String generateOutputSchema(Type type) {
        return generate(type, SchemaPurpose.OUTPUT);
    }

    private String generate(Type type, SchemaPurpose schemaPurpose) {
        Assert.notNull(type, "type is required");
        Assert.notNull(schemaPurpose, "schemaPurpose is required");
        try {
            JsonMapper objectMapper = new JsonMapper();
            ObjectNode schema = (ObjectNode) objectMapper.readTree(JsonSchemaGenerator.generateForType(type));
            new SchemaProjection(schema, schemaPurpose).apply(type, null, schema);
            return objectMapper.writeValueAsString(schema);
        } catch (JacksonException exception) {
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

    private enum SchemaPurpose {
        INPUT,
        OUTPUT
    }

    /**
     * 將目前使用的 Jakarta constraint 投影為等價 JSON Schema keyword
     * NotBlank 以 minLength 與非空白 pattern 近似，因 JSON Schema 沒有 Java 空白字元判斷的完全等價 keyword
     */
    private static final class SchemaProjection {

        private final ObjectNode root;
        private final SchemaPurpose schemaPurpose;

        private SchemaProjection(ObjectNode root, SchemaPurpose schemaPurpose) {
            this.root = root;
            this.schemaPurpose = schemaPurpose;
        }

        private void apply(Type type, AnnotatedType annotatedType, JsonNode candidate) {
            ObjectNode schema = resolve(candidate);
            if (Objects.nonNull(annotatedType)) {
                applyConstraints(schema, annotatedType.getAnnotations());
            }
            Class<?> rawType = rawType(type);
            if (rawType.isRecord()) {
                applyRecord(rawType, schema);
                return;
            }
            JsonSubTypes subTypes = rawType.getAnnotation(JsonSubTypes.class);
            JsonTypeInfo typeInfo = rawType.getAnnotation(JsonTypeInfo.class);
            if (Objects.nonNull(subTypes) && Objects.nonNull(typeInfo)) {
                applyPolymorphicBranches(subTypes, typeInfo, schema);
                return;
            }
            if (type instanceof ParameterizedType parameterizedType) {
                applyParameterized(parameterizedType, annotatedType, schema, rawType);
                return;
            }
            if (rawType.isArray()) {
                JsonNode items = schema.get("items");
                if (Objects.nonNull(items)) {
                    apply(rawType.getComponentType(), null, items);
                }
            }
        }

        private void applyRecord(Class<?> recordType, ObjectNode schema) {
            ObjectNode objectSchema = objectSchema(schema);
            if (Objects.isNull(objectSchema)) {
                return;
            }
            ArrayNode required = objectSchema.withArray("required");
            required.removeAll();
            for (RecordComponent component : recordType.getRecordComponents()) {
                JsonNode property = objectSchema.path("properties").get(component.getName());
                if (Objects.isNull(property)) {
                    continue;
                }
                if (isRequired(component)) {
                    required.add(component.getName());
                }
                apply(component.getGenericType(), component.getAnnotatedType(), property);
            }
        }

        private void applyPolymorphicBranches(
                JsonSubTypes subTypes,
                JsonTypeInfo typeInfo,
                ObjectNode schema) {
            for (JsonSubTypes.Type subType : subTypes.value()) {
                ObjectNode branch = branchFor(schema, typeInfo.property(), subType.name());
                apply(subType.value(), null, branch);
            }
        }

        private void applyParameterized(
                ParameterizedType parameterizedType,
                AnnotatedType annotatedType,
                ObjectNode schema,
                Class<?> rawType) {
            Type[] actualTypes = parameterizedType.getActualTypeArguments();
            AnnotatedType[] annotatedTypes = annotatedArguments(annotatedType);
            if (Optional.class.isAssignableFrom(rawType) && actualTypes.length == 1) {
                apply(actualTypes[0], annotatedType(annotatedTypes, 0), schema);
                return;
            }
            if (Collection.class.isAssignableFrom(rawType) && actualTypes.length == 1) {
                JsonNode items = schema.get("items");
                if (Objects.nonNull(items)) {
                    apply(actualTypes[0], annotatedType(annotatedTypes, 0), items);
                }
                return;
            }
            if (Map.class.isAssignableFrom(rawType) && actualTypes.length == 2) {
                JsonNode values = schema.get("additionalProperties");
                if (Objects.nonNull(values) && values.isObject()) {
                    apply(actualTypes[1], annotatedType(annotatedTypes, 1), values);
                }
            }
        }

        private void applyConstraints(ObjectNode schema, Annotation[] annotations) {
            NotBlank notBlank = annotation(annotations, NotBlank.class);
            if (Objects.nonNull(notBlank)) {
                schema.put("minLength", 1);
                schema.put("pattern", ".*\\S.*");
            }
            NotEmpty notEmpty = annotation(annotations, NotEmpty.class);
            if (Objects.nonNull(notEmpty)) {
                applyNotEmpty(schema);
            }
            Size size = annotation(annotations, Size.class);
            if (Objects.nonNull(size)) {
                applySize(schema, size);
            }
            Pattern pattern = annotation(annotations, Pattern.class);
            if (Objects.nonNull(pattern) && isPortableMcpPattern(pattern.regexp())) {
                applyPattern(schema, pattern.regexp());
            }
            Min min = annotation(annotations, Min.class);
            if (Objects.nonNull(min)) {
                schema.put("minimum", min.value());
            }
            Max max = annotation(annotations, Max.class);
            if (Objects.nonNull(max)) {
                schema.put("maximum", max.value());
            }
            PositiveOrZero positiveOrZero = annotation(annotations, PositiveOrZero.class);
            if (Objects.nonNull(positiveOrZero)) {
                schema.put("minimum", 0);
            }
        }

        private void applyNotEmpty(ObjectNode schema) {
            if (schema.has("items")) {
                schema.put("minItems", 1);
                return;
            }
            if (schema.has("properties")) {
                schema.put("minProperties", 1);
                return;
            }
            schema.put("minLength", 1);
        }

        private void applyPattern(ObjectNode schema, String pattern) {
            JsonNode existingPattern = schema.get("pattern");
            if (Objects.nonNull(existingPattern) && !pattern.equals(existingPattern.asText())) {
                schema.withArray("allOf").addObject().put("pattern", existingPattern.asText());
            }
            schema.put("pattern", pattern);
        }

        private boolean isRequired(RecordComponent component) {
            return schemaPurpose == SchemaPurpose.OUTPUT || McpInputRequiredness.isRequired(component);
        }

        private void applySize(ObjectNode schema, Size size) {
            if (schema.has("items")) {
                schema.put("minItems", size.min());
                schema.put("maxItems", size.max());
                return;
            }
            if (schema.has("properties")) {
                schema.put("minProperties", size.min());
                schema.put("maxProperties", size.max());
                return;
            }
            schema.put("minLength", size.min());
            schema.put("maxLength", size.max());
        }

        private ObjectNode branchFor(ObjectNode schema, String discriminator, String discriminatorValue) {
            JsonNode branches = schema.get("anyOf");
            if (!(branches instanceof ArrayNode arrayNode)) {
                throw new IllegalStateException("generated polymorphic MCP schema has no anyOf branches");
            }
            for (JsonNode candidate : arrayNode) {
                ObjectNode branch = resolve(candidate);
                JsonNode constant = branch.path("properties").path(discriminator).path("const");
                if (discriminatorValue.equals(constant.asText())) {
                    return branch;
                }
            }
            throw new IllegalStateException("generated polymorphic MCP schema has no discriminator branch");
        }

        private ObjectNode objectSchema(ObjectNode schema) {
            if (schema.has("properties")) {
                return schema;
            }
            return null;
        }

        private ObjectNode resolve(JsonNode candidate) {
            if (!(candidate instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("generated MCP schema node is not an object");
            }
            JsonNode reference = objectNode.get("$ref");
            if (Objects.isNull(reference)) {
                return objectNode;
            }
            String value = reference.asText();
            if (!value.startsWith("#/")) {
                throw new IllegalStateException("generated MCP schema has an unsupported reference");
            }
            JsonNode resolved = root.at(value.substring(1));
            if (!(resolved instanceof ObjectNode resolvedObject)) {
                throw new IllegalStateException("generated MCP schema reference is unresolved");
            }
            return resolvedObject;
        }

        private static Class<?> rawType(Type type) {
            if (type instanceof Class<?> classType) {
                return classType;
            }
            if (type instanceof ParameterizedType parameterizedType
                    && parameterizedType.getRawType() instanceof Class<?> classType) {
                return classType;
            }
            throw new IllegalStateException("generated MCP schema has an unsupported Java type");
        }

        private static AnnotatedType[] annotatedArguments(AnnotatedType annotatedType) {
            if (annotatedType instanceof AnnotatedParameterizedType parameterizedType) {
                return parameterizedType.getAnnotatedActualTypeArguments();
            }
            return new AnnotatedType[0];
        }

        private static AnnotatedType annotatedType(AnnotatedType[] types, int index) {
            return index < types.length ? types[index] : null;
        }

        private static <A extends Annotation> A annotation(Annotation[] annotations, Class<A> type) {
            for (Annotation annotation : annotations) {
                if (type.isInstance(annotation)) {
                    return type.cast(annotation);
                }
            }
            return null;
        }
    }
}
