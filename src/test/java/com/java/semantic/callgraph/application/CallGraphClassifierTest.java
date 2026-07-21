package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphClassifierTest {

    private final CallGraphClassifier classifier = new CallGraphClassifier();

    @Test
    void should_support_simple_and_fully_qualified_stereotype_annotations() {
        assertThat(classifier.callType(metadata("ServiceType", List.of("Service"), List.of(), false), "run"))
                .isEqualTo(CallType.INTERNAL_SERVICE);
        assertThat(classifier.callType(metadata(
                "ControllerType", List.of("org.springframework.web.bind.annotation.RestController"), List.of(), false),
                "run"))
                .isEqualTo(CallType.INTERNAL_CONTROLLER);
    }

    @Test
    void should_classify_feign_as_opaque_rpc() {
        ClassMetadata metadata = metadata(
                "InventoryClient", List.of("org.springframework.cloud.openfeign.FeignClient"), List.of(), false);

        assertThat(classifier.callType(metadata, "inventory")).isEqualTo(CallType.RPC_CLIENT);
        assertThat(classifier.resolutionStrategy(metadata, "inventory"))
                .isEqualTo(ResolutionStrategy.FEIGN_CLIENT);
    }

    @Test
    void should_classify_mybatis_with_and_without_sql_from_method_evidence() {
        MethodSignature withSql = method("find", "Order", "SELECT 1", SqlSource.ANNOTATION);
        MethodSignature withoutSql = method("find", "String", null, null);
        ClassMetadata mapper = metadata(
                "OrderMapper", List.of("org.apache.ibatis.annotations.Mapper"), List.of(withSql, withoutSql), false);

        assertThat(classifier.callType(mapper, "find")).isEqualTo(CallType.DATA_ACCESS);
        assertThat(classifier.resolutionStrategy(mapper, withSql))
                .isEqualTo(ResolutionStrategy.MYBATIS_MAPPER);
        assertThat(classifier.resolutionStrategy(mapper, withoutSql))
                .isEqualTo(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE);
    }

    @Test
    void should_model_data_getter_setter_and_object_method_capabilities() {
        ClassMetadata data = metadataWithFields(
                "OrderDto", List.of("lombok.Data"), false, field("orderNo", "String"));

        assertThat(classifier.callType(data, "getOrderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "setOrderNo", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "equals", List.of("Object"), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "hashCode", List.of(), "int", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "toString", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "builder")).isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_model_getter_capabilities_without_setters_or_object_methods() {
        ClassMetadata getter = metadataWithFields(
                "OrderDto", List.of("lombok.Getter"), false,
                field("orderNo", "String"), field("paid", "boolean"));

        assertThat(classifier.callType(getter, "getOrderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(getter, "isPaid", List.of(), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(getter, "setOrderNo")).isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(getter, "toString")).isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_model_setter_capabilities_without_getters_or_object_methods() {
        ClassMetadata setter = metadataWithFields(
                "OrderDto", List.of("lombok.Setter"), false, field("orderNo", "String"));

        assertThat(classifier.callType(setter, "setOrderNo", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(setter, "getOrderNo")).isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(setter, "equals")).isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_model_value_getter_and_object_method_capabilities_without_setters() {
        ClassMetadata value = metadataWithFields(
                "OrderDto", List.of("lombok.Value"), false, field("orderNo", "String"));

        assertThat(classifier.callType(value, "getOrderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(value, "equals", List.of("Object"), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(value, "hashCode", List.of(), "int", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(value, "toString", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(value, "setOrderNo")).isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_model_builder_capabilities_without_accessors_or_object_methods() {
        ClassMetadata builder = metadata("OrderDto", List.of("lombok.Builder"), List.of(), false);
        ClassMetadata superBuilder = metadata("SpecialOrderDto", List.of("lombok.experimental.SuperBuilder"), List.of(), false);
        ClassMetadata nestedBuilder = metadata(
                "OrderDto.OrderDtoBuilder", List.of(), List.of(), false);
        ClassMetadata nestedSuperBuilder = metadata(
                "SpecialOrderDto.SpecialOrderDtoBuilder", List.of(), List.of(), false);
        ClassMetadata unrelatedBuilder = metadata(
                "OrderDto.UnrelatedBuilder", List.of(), List.of(), false);

        assertThat(classifier.callType(
                builder, "builder", List.of(), "OrderDtoBuilder", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(
                superBuilder, "builder", List.of(), "SpecialOrderDtoBuilder", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(builder, "build")).isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(superBuilder, "build")).isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(
                nestedBuilder, "build", List.of(), "OrderDto", Optional.of(builder)))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(
                nestedBuilder, "build", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(
                nestedSuperBuilder, "build", List.of(), "SpecialOrderDto", Optional.of(superBuilder)))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(
                unrelatedBuilder, "build", List.of(), Optional.of(builder)))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(builder, "getOrderNo")).isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(builder, "toString")).isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_require_generating_annotation_evidence_for_fluent_field_methods() {
        ClassMetadata accessorsOnly = metadata(
                "AccessorsOnly", List.of("lombok.experimental.Accessors"), List.of(), true);
        ClassMetadata fluentGetter = metadata(
                "FluentGetter", List.of("lombok.Getter", "lombok.experimental.Accessors"), List.of(), true);
        ClassMetadata fluentSetter = metadata(
                "FluentSetter", List.of("lombok.Setter", "lombok.experimental.Accessors"), List.of(), true);
        ClassMetadata fluentData = metadata(
                "FluentData", List.of("lombok.Data", "lombok.experimental.Accessors"), List.of(), true);
        ClassMetadata fluentValue = metadata(
                "FluentValue", List.of("lombok.Value", "lombok.experimental.Accessors"), List.of(), true);
        ClassMetadata fluentBuilder = metadata(
                "FluentBuilder", List.of("lombok.Builder", "lombok.experimental.Accessors"), List.of(), true);

        assertThat(classifier.callType(accessorsOnly, "orderNo", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluentGetter, "orderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluentGetter, "orderNo", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluentGetter, "missing", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(
                fluentSetter, "orderNo", List.of("String"), "FluentSetter", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluentSetter, "orderNo", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluentData, "orderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(
                fluentData, "orderNo", List.of("String"), "FluentData", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluentValue, "orderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluentValue, "orderNo", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluentBuilder, "orderNo", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_require_matching_field_and_primitive_boolean_type_for_standard_accessors() {
        ClassMetadata data = metadataWithFields(
                "OrderDto", List.of("lombok.Data"), false,
                field("orderNo", "String"), field("paid", "boolean"), field("boxed", "Boolean"));

        assertThat(classifier.callType(data, "getOrderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "setOrderNo", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "getMissing", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "setMissing", List.of("String"), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "isPaid", List.of(), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "isBoxed", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "getBoxed", List.of(), "Boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "isMissing", List.of(), Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_require_exact_generated_method_signatures() {
        ClassMetadata data = metadataWithFields(
                "OrderDto", List.of("lombok.Data"), false,
                field("orderNo", "String"), field("paid", "boolean"));
        ClassMetadata fluent = metadataWithFields(
                "FluentOrder", List.of("lombok.Data", "lombok.experimental.Accessors"), true,
                field("orderNo", "String"));
        ClassMetadata nonChainedFluent = metadataWithFields(
                "NonChainedOrder", List.of("lombok.Data", "lombok.experimental.Accessors"), true, false,
                field("orderNo", "String"));
        ClassMetadata outer = metadata("OrderDto", List.of("lombok.Builder"), List.of(), false);
        ClassMetadata nested = metadata("OrderDto.OrderDtoBuilder", List.of(), List.of(), false);

        assertThat(classifier.callType(data, "getOrderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "getOrderNo", List.of(), "Long", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "setOrderNo", List.of("String"), "void", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "setOrderNo", List.of("Long"), "void", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "isPaid", List.of(), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "isPaid", List.of(), "Boolean", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluent, "orderNo", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluent, "orderNo", List.of(), "Long", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluent, "orderNo", List.of("String"), "FluentOrder", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(fluent, "orderNo", List.of("Long"), "FluentOrder", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluent, "orderNo", List.of("String"), "void", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluent, "orderNo", List.of("String"), "", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(fluent, "orderNo", List.of("String"), "Other", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(nonChainedFluent, "orderNo", List.of("String"), "void", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(
                nonChainedFluent, "orderNo", List.of("String"), "NonChainedOrder", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(nonChainedFluent, "orderNo", List.of("String"), "", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);

        assertThat(classifier.callType(data, "toString", List.of(), "String", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "toString", List.of("Object"), "String", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "hashCode", List.of(), "int", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "hashCode", List.of(), "long", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(data, "equals", List.of("Object"), "boolean", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(data, "equals", List.of("String"), "boolean", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);

        assertThat(classifier.callType(outer, "builder", List.of(), "OrderDtoBuilder", Optional.empty()))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(outer, "builder", List.of("String"), "OrderDtoBuilder", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(outer, "builder", List.of(), "OtherBuilder", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(outer, "builder", List.of(), "", Optional.empty()))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(nested, "build", List.of(), "OrderDto", Optional.of(outer)))
                .isEqualTo(CallType.GENERATED_CODE);
        assertThat(classifier.callType(nested, "build", List.of(), "Other", Optional.of(outer)))
                .isEqualTo(CallType.INTERNAL_CLASS);
        assertThat(classifier.callType(nested, "build", List.of(), "", Optional.of(outer)))
                .isEqualTo(CallType.INTERNAL_CLASS);
    }

    @Test
    void should_index_duplicate_classes_and_complete_method_ids_in_canonical_order() {
        ClassMetadata zeta = metadata("OrderService", List.of("Service"), List.of(method("run", null, null)), false,
                "zeta/OrderService.java");
        ClassMetadata alpha = metadata("OrderService", List.of("Service"), List.of(method("run", null, null)), false,
                "alpha/OrderService.java");
        RepositorySyntaxIndex index = new RepositorySyntaxIndex("orders", new RepositorySyntax(List.of(), List.of(zeta, alpha)));
        MethodId methodId = new MethodId("orders", "com.example", "OrderService", "run", List.of("Order"));

        assertThat(index.classes("com.example.OrderService"))
                .extracting(ClassMetadata::filePath)
                .containsExactly("alpha/OrderService.java", "zeta/OrderService.java");
        assertThat(index.methods(methodId)).hasSize(2);
        assertThat(index.methods(new MethodId("other", "com.example", "OrderService", "run", List.of("Order"))))
                .hasSize(0);
    }

    @Test
    void should_order_duplicate_complete_method_ids_independently_of_input_order() {
        MethodSignature laterCharacter = method(
                "run", "Order", List.of("Alpha"), 2, 8, 4, 5, "alpha source");
        MethodSignature earlyCharacter = method(
                "run", "Order", List.of("Zeta"), 2, 3, 4, 5, "zeta source");
        MethodSignature sameRangeBeta = method(
                "run", "Order", List.of("Beta"), 6, 1, 7, 2, "same source");
        MethodSignature sameRangeAlpha = method(
                "run", "Order", List.of("Alpha"), 6, 1, 7, 2, "same source");
        ClassMetadata forward = metadata(
                "OrderService", List.of("Service"), List.of(laterCharacter, sameRangeBeta), false,
                "same/OrderService.java");
        ClassMetadata reverse = metadata(
                "OrderService", List.of("Service"), List.of(sameRangeAlpha, earlyCharacter), false,
                "same/OrderService.java");
        ClassMetadata forwardMethodsReversed = metadata(
                "OrderService", List.of("Service"), List.of(sameRangeBeta, laterCharacter), false,
                "same/OrderService.java");
        ClassMetadata reverseMethodsReversed = metadata(
                "OrderService", List.of("Service"), List.of(earlyCharacter, sameRangeAlpha), false,
                "same/OrderService.java");
        MethodId methodId = new MethodId("orders", "com.example", "OrderService", "run", List.of("Order"));

        RepositorySyntaxIndex first = new RepositorySyntaxIndex(
                "orders", new RepositorySyntax(List.of(), List.of(forward, reverse)));
        RepositorySyntaxIndex second = new RepositorySyntaxIndex(
                "orders", new RepositorySyntax(List.of(), List.of(reverseMethodsReversed, forwardMethodsReversed)));

        assertThat(first.methods(methodId)).extracting(method -> method.range().start().line())
                .containsExactly(2, 2, 6, 6);
        assertThat(first.methods(methodId)).extracting(method -> method.range().start().character())
                .containsExactly(3, 8, 1, 1);
        assertThat(first.methods(methodId)).extracting(MethodSignature::annotations)
                .containsExactly(List.of("Zeta"), List.of("Alpha"), List.of("Alpha"), List.of("Beta"));
        assertThat(first.methods(methodId)).extracting(method -> method.source().text())
                .containsExactly("zeta source", "alpha source", "same source", "same source");
        assertThat(second.methods(methodId)).containsExactlyElementsOf(first.methods(methodId));
    }

    private ClassMetadata metadata(
            String className, List<String> annotations, List<MethodSignature> methods, boolean fluent) {
        return metadata(className, annotations, methods, fluent, className + ".java");
    }

    private ClassMetadata metadataWithFields(
            String className, List<String> annotations, boolean fluent, FieldInfo... fields) {
        return metadataWithFields(className, annotations, fluent, fluent, fields);
    }

    private ClassMetadata metadataWithFields(
            String className,
            List<String> annotations,
            boolean fluent,
            boolean chained,
            FieldInfo... fields) {
        SyntaxRange range = range();
        return new ClassMetadata(
                className,
                "com.example",
                "com.example." + className,
                className + ".java",
                TypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                annotations,
                List.of(),
                List.of(fields),
                List.of(),
                fluent,
                chained,
                List.of(),
                range,
                new SourceSlice(range, "class " + className + " {}"),
                false,
                List.of());
    }

    private FieldInfo field(String name, String type) {
        return new FieldInfo(
                name, type, List.of(), "", new TypeReference(type, type, List.of(), false));
    }

    private ClassMetadata metadata(
            String className, List<String> annotations, List<MethodSignature> methods, boolean fluent, String filePath) {
        SyntaxRange range = range();
        return new ClassMetadata(
                className,
                "com.example",
                "com.example." + className,
                filePath,
                TypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                annotations,
                List.of(),
                fluent ? List.of(new FieldInfo(
                        "orderNo", "String", List.of(), "",
                        new TypeReference("String", "java.lang.String", List.of(), false))) : List.of(),
                methods,
                fluent,
                fluent,
                List.of(),
                range,
                new SourceSlice(range, "class " + className + " {}"),
                false,
                List.of());
    }

    private MethodSignature method(String name, String sql, SqlSource sqlSource) {
        return method(name, "Order", sql, sqlSource);
    }

    private MethodSignature method(String name, String parameterType, String sql, SqlSource sqlSource) {
        SyntaxRange range = range();
        return new MethodSignature(
                name,
                List.of(parameterType),
                List.of(),
                sql,
                sqlSource,
                1,
                1,
                range,
                new SourceSlice(range, name),
                List.of(),
                Optional.empty(),
                List.of());
    }

    private MethodSignature method(
            String name,
            String parameterType,
            List<String> annotations,
            int startLine,
            int startCharacter,
            int endLine,
            int endCharacter,
            String source) {
        SyntaxRange range = new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
        return new MethodSignature(
                name, List.of(parameterType), annotations, null, null, startLine + 1, endLine + 1, range,
                new SourceSlice(range, source), List.of(), Optional.empty(), List.of());
    }

    private SyntaxRange range() {
        SyntaxPosition position = new SyntaxPosition(0, 0);
        return new SyntaxRange(position, position);
    }
}
