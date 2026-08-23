package com.java.semantic.callgraph.application;

import com.java.semantic.syntax.domain.SourceTypeKind;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.NamedTypeReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedMemberEvidenceTest {

    private final GeneratedMemberEvidence evidence = new GeneratedMemberEvidence();

    @Test
    void should_match_data_getter_without_source_declaration() {
        SourceTypeMetadata order = metadata("com.example.Order", List.of("Data"),
                List.of(field("total", "BigDecimal")), List.of(), false);
        SyntaxInvocation call = invocation(InvocationKind.METHOD, "order.getTotal()", "com.example.Order");

        Optional<EvidenceMatch> match = evidence.evaluate(order, call);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.LOMBOK_GENERATED);
        assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo("com.example.Order#getTotal()");
    }

    @Test
    void should_not_match_when_source_declares_same_signature() {
        SourceMethodMetadata handWritten = method("getTotal", List.of());
        SourceTypeMetadata order = metadata("com.example.Order", List.of("Data"),
                List.of(field("total", "BigDecimal")), List.of(handWritten), false);
        SyntaxInvocation call = invocation(InvocationKind.METHOD, "order.getTotal()", "com.example.Order");

        assertThat(evidence.evaluate(order, call)).isEmpty();
    }

    @Test
    void should_not_match_required_args_constructor() {
        SourceTypeMetadata invoice = metadata("com.example.Invoice", List.of("RequiredArgsConstructor"),
                List.of(field("id", "String")), List.of(), false);
        SyntaxInvocation call = invocation(InvocationKind.CONSTRUCTOR, "new Invoice(id)", "com.example.Invoice");

        assertThat(evidence.evaluate(invoice, call)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remainingRules")
    void should_evaluate_remaining_wave_one_rules(
            String caseName, SourceTypeMetadata receiverType, SyntaxInvocation call, Optional<String> expectedSymbol) {
        Optional<EvidenceMatch> match = evidence.evaluate(receiverType, call);

        if (expectedSymbol.isPresent()) {
            assertThat(match).isPresent();
            assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.LOMBOK_GENERATED);
            assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo(expectedSymbol.orElseThrow());
        } else {
            assertThat(match).isEmpty();
        }
    }

    private static Stream<Arguments> remainingRules() {
        SourceTypeMetadata setterOwner = metadata("com.example.Order", List.of("Setter"),
                List.of(field("total", "BigDecimal")), List.of(), false);
        SourceTypeMetadata valueOwner = metadata("com.example.Order", List.of("Value"),
                List.of(field("total", "BigDecimal")), List.of(), false);
        SourceTypeMetadata fluentGetterOwner = metadata("com.example.Order", List.of("Getter"),
                List.of(field("total", "BigDecimal")), List.of(), true);
        SourceTypeMetadata fluentSetterOwner = metadata("com.example.Order", List.of("Setter"),
                List.of(field("total", "BigDecimal")), List.of(), true);
        SourceTypeMetadata builderOwner = metadata("com.example.Order", List.of("Builder"),
                List.of(field("total", "BigDecimal"), field("carrier", "String")), List.of(), false);
        SourceTypeMetadata noArgsOwner = metadata("com.example.Order", List.of("NoArgsConstructor"),
                List.of(field("id", "String")), List.of(), false);
        SourceTypeMetadata allArgsOwner = metadata("com.example.Order", List.of("AllArgsConstructor"),
                List.of(field("id", "String"), field("total", "BigDecimal")), List.of(), false);

        return Stream.of(
                Arguments.of("setter matches @Setter field", setterOwner,
                        invocation(InvocationKind.METHOD, "order.setTotal(five)", "com.example.Order"),
                        Optional.of("com.example.Order#setTotal(..)")),
                Arguments.of("object method matches @Value toString", valueOwner,
                        invocation(InvocationKind.METHOD, "order.toString()", "com.example.Order"),
                        Optional.of("com.example.Order#toString()")),
                Arguments.of("fluent accessor matches bare field getter", fluentGetterOwner,
                        invocation(InvocationKind.METHOD, "order.total()", "com.example.Order"),
                        Optional.of("com.example.Order#getTotal()")),
                Arguments.of("fluent accessor matches bare field setter with one argument", fluentSetterOwner,
                        invocation(InvocationKind.METHOD, "order.total(five)", "com.example.Order"),
                        Optional.of("com.example.Order#setTotal(..)")),
                Arguments.of("builder entry matches @Builder builder()", builderOwner,
                        invocation(InvocationKind.METHOD, "Order.builder()", "com.example.Order"),
                        Optional.of("com.example.Order#builder()")),
                Arguments.of("builder chain matches build() on the nested builder", builderOwner,
                        invocation(InvocationKind.METHOD, "orderBuilder.build()", "com.example.Order.OrderBuilder"),
                        Optional.of("com.example.Order.OrderBuilder#build()")),
                Arguments.of("builder chain matches a one-argument field setter", builderOwner,
                        invocation(InvocationKind.METHOD, "orderBuilder.carrier(x)", "com.example.Order.OrderBuilder"),
                        Optional.of("com.example.Order.OrderBuilder#carrier(..)")),
                Arguments.of("builder chain does not match a zero-argument call sharing a field name", builderOwner,
                        invocation(InvocationKind.METHOD, "orderBuilder.carrier()", "com.example.Order.OrderBuilder"),
                        Optional.<String>empty()),
                Arguments.of("no-args constructor matches @NoArgsConstructor", noArgsOwner,
                        invocation(InvocationKind.CONSTRUCTOR, "new Order()", "com.example.Order"),
                        Optional.of("com.example.Order#<init>(0)")),
                Arguments.of("all-args constructor matches arg count equal to field count", allArgsOwner,
                        invocation(InvocationKind.CONSTRUCTOR, "new Order(id, total)", "com.example.Order"),
                        Optional.of("com.example.Order#<init>(2)")),
                Arguments.of("all-args constructor does not match on arity mismatch", allArgsOwner,
                        invocation(InvocationKind.CONSTRUCTOR, "new Order(id)", "com.example.Order"),
                        Optional.<String>empty()),
                Arguments.of("getter matches through a chained receiver expression", valueOwner,
                        invocation(InvocationKind.METHOD, "service.findOrder(id).getTotal()", "com.example.Order"),
                        Optional.of("com.example.Order#getTotal()")),
                Arguments.of("setter does not match a two-argument call through a chained receiver expression",
                        setterOwner,
                        invocation(InvocationKind.METHOD, "service.findOrder(id).setTotal(a, b)", "com.example.Order"),
                        Optional.<String>empty()),
                Arguments.of("object method matches @Value equals with one argument", valueOwner,
                        invocation(InvocationKind.METHOD, "order.equals(a)", "com.example.Order"),
                        Optional.of("com.example.Order#equals(..)")),
                Arguments.of("object method does not match equals with two arguments", valueOwner,
                        invocation(InvocationKind.METHOD, "order.equals(a, b)", "com.example.Order"),
                        Optional.<String>empty()));
    }

    private static SourceTypeMetadata metadata(
            String fullyQualifiedName,
            List<String> annotations,
            List<SourceFieldMetadata> fields,
            List<SourceMethodMetadata> methods,
            boolean fluent) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        String simpleName = fullyQualifiedName.substring(lastDot + 1);
        String packageName = lastDot >= 0 ? fullyQualifiedName.substring(0, lastDot) : "";
        String sourceFile = "src/main/java/" + fullyQualifiedName.replace('.', '/') + ".java";
        SyntaxRange range = range(0, 0, 10, 0);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                simpleName, packageName, fullyQualifiedName,
                sourceFile,
                SourceTypeKind.CLASS, false, List.of(), List.of(), annotations, List.of(),
                fields, methods, fluent, fluent, List.of(), range,
                new SourceRange(sourceFile, range), false, List.of());
    }

    private static SourceFieldMetadata field(String name, String type) {
        return new SourceFieldMetadata(name, type, "", new NamedTypeReference(
                type, type, Optional.empty(), false), List.of());
    }

    private static SourceMethodMetadata method(String name, List<String> paramTypes) {
        SyntaxRange range = range(0, 0, 1, 0);
        return new SourceMethodMetadata(
                name, paramTypes, null, Optional.empty(),
                new SourceRange("Generated.java", range), List.of(), Optional.empty(), List.of(), List.of(),
                List.of(), range.start(), MethodTargetResolution.unresolved("test-fixture"), true, false, true);
    }

    private static SyntaxInvocation invocation(InvocationKind kind, String expression, String receiverDeclaration) {
        SyntaxRange range = range(0, 0, 0, expression.length());
        return new SyntaxInvocation(
                kind, range, expression, "receiver", receiverDeclaration, "", Optional.empty(), range.start());
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
    }
}
