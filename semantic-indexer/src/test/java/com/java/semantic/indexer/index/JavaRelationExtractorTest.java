package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract.Declaration;
import com.java.semantic.model.index.IndexContract.DeclarationKind;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.ScopeKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class JavaRelationExtractorTest {
    @TempDir Path temporary;

    @Test void declared_overloads_and_interface_calls_keep_exact_targets_without_inventing_missing_types() throws Exception {
        String source = """
                interface Sink { void save(Object value); }
                class Demo implements Sink {
                    public void save(Object value) {}
                    Demo next;
                    String text;
                    public void save(String value) {}
                    void run(Sink target, Missing unknown) {
                        target.save(1);
                        save("x");
                        java.util.List.of("x");
                        this.next.text.length();
                        unknown.work();
                    }
                }
                """;
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Demo.java", source))) {
            List<Fact> facts = fixture.extract();
            assertSourceTarget(call(fixture, facts, "target.save(1)"), "Sink.save(java.lang.Object)");
            assertSourceTarget(call(fixture, facts, "save(\"x\")"), "Demo.save(java.lang.String)");
            Fact jdk = call(fixture, facts, "java.util.List.of(\"x\")");
            assertThat(jdk.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(jdk.target().orElseThrow().external().orElseThrow().signature()).isEqualTo("java.util.List.of(E)");
            assertThat(jdk.target().orElseThrow().external().orElseThrow().module()).contains("java.base");
            Fact receiver = facts.stream().filter(fact -> fact.kind() == Kind.REFERENCE
                    && fixture.slice(fact).equals("java.util.List")).findFirst().orElseThrow();
            assertThat(receiver.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(receiver.target().orElseThrow().external().orElseThrow().qualifiedOwner()).isEqualTo("java.util.List");
            assertThat(facts.stream().filter(fact -> fact.kind() == Kind.REFERENCE).map(fixture::slice))
                    .doesNotContain("java", "java.util");
            assertThat(facts.stream().filter(fact -> fact.kind() == Kind.REFERENCE
                    && List.of("this.next", "this.next.text").contains(fixture.slice(fact))))
                    .allSatisfy(fact -> {
                        assertThat(fact.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
                        assertThat(fact.target().orElseThrow().source().orElseThrow().declaration().kind())
                                .isEqualTo(DeclarationKind.FIELD);
                        assertThat(fact.target().orElseThrow().source().orElseThrow().declaration().name())
                                .isEqualTo(fixture.slice(fact).equals("this.next") ? "next" : "text");
                    }).hasSize(2);
            Fact missing = call(fixture, facts, "unknown.work()");
            assertThat(missing.resolutionState()).isEqualTo(ResolutionState.UNRESOLVED);
            assertThat(missing.unresolvedReason()).contains(Reason.MISSING_TYPE);
            assertThat(missing.target()).isEmpty();
            assertThat(facts).anySatisfy(fact -> {
                assertThat(fact.kind()).isEqualTo(Kind.OVERRIDES);
                assertSourceTarget(fact, "Sink.save(java.lang.Object)");
            });
        }
    }

    @Test void lambda_initializers_shadowing_and_raw_unicode_do_not_claim_outer_execution_or_field_edges() throws Exception {
        String escaped = "\\" + "u0068";
        String source = "class Demo {\r\n"
                + " String value;\r\n"
                + " static { helper(); }\r\n"
                + " String text = helper();\r\n"
                + " static String helper() { return \"漢\"; }\r\n"
                + " void run(String value) {\r\n"
                + "   Runnable r = () -> " + escaped + "elper();\r\n"
                + "   value.length();\r\n"
                + "   this.value.length();\r\n"
                + " }\r\n}\r\n";
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Demo.java", source))) {
            List<Fact> facts = fixture.extract();
            Map<String, Declaration> declarations = fixture.units.get("Demo.java").syntax().declarations().stream()
                    .collect(Collectors.toMap(Declaration::symbolId, declaration -> declaration));
            for (Fact fact : facts) {
                Declaration container = declarations.get(fact.source().containerSymbolId().orElseThrow());
                assertThat(container.range().contains(fact.source().range()))
                        .as("container must cover occurrence %s", fixture.slice(fact)).isTrue();
                assertThat(container.range().contains(fact.source().executionScope().range()))
                        .as("container must cover execution scope for %s", fixture.slice(fact)).isTrue();
            }
            Fact lambda = call(fixture, facts, escaped + "elper()");
            assertThat(lambda.source().executionScope().kind()).isEqualTo(ScopeKind.LAMBDA);
            assertThat(lambda.source().range().startLine()).isEqualTo(7);
            assertSourceTarget(lambda, "Demo.helper()");
            assertThat(facts.stream().filter(fact -> fact.kind() == Kind.CALL && fixture.slice(fact).equals("helper()")))
                    .extracting(fact -> fact.source().executionScope().kind())
                    .containsExactly(ScopeKind.STATIC_INITIALIZER, ScopeKind.FIELD_INITIALIZER);
            String field = fixture.units.get("Demo.java").syntax().declarations().stream()
                    .filter(declaration -> declaration.kind() == DeclarationKind.FIELD && declaration.name().equals("value"))
                    .findFirst().orElseThrow().symbolId();
            assertThat(facts.stream().filter(fact -> fact.target().flatMap(RelationContract.Target::source)
                    .filter(target -> target.declaration().symbolId().equals(field)).isPresent()))
                    .extracting(fixture::slice).containsExactly("this.value");
        }
    }

    @Test void catalogue_order_scope_duplicates_and_application_classpath_never_silently_rebind() throws Exception {
        Map<String, String> sources = Map.of(
                "src/Use.java", "class Use { void call(Target target) { target.run(); com.github.javaparser.JavaParser.parse(\"x\"); } }",
                "src/Target.java", "class Target { void run() {} }",
                "excluded/Target.java", "class Target { void run(int wrong) {} }");
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary.resolve("scoped"), sources, List.of(), List.of("src"))) {
            List<Fact> facts = fixture.extract();
            assertSourceTarget(call(fixture, facts, "target.run()"), "Target.run()");
            Fact application = call(fixture, facts, "com.github.javaparser.JavaParser.parse(\"x\")");
            assertThat(application.resolutionState()).isEqualTo(ResolutionState.UNRESOLVED);
            assertThat(application.target()).isEmpty();
            assertThat(facts).noneMatch(fact -> fact.source().path().startsWith("excluded/"));
        }
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary.resolve("duplicate"), sources)) {
            Fact duplicate = call(fixture, fixture.extract(), "target.run()");
            assertThat(duplicate.resolutionState()).isEqualTo(ResolutionState.UNRESOLVED);
            assertThat(duplicate.unresolvedReason()).contains(Reason.DUPLICATE_SOURCE_TYPE);
            assertThat(duplicate.target()).isEmpty();
        }
    }

    @Test void compact_record_constructor_keeps_raw_call_and_nearest_written_constructor_scope() throws Exception {
        String source = "record R(int value) { R { validate(value); } static void validate(int value) {} }";
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("R.java", source))) {
            Fact occurrence = call(fixture, fixture.extract(), "validate(value)");
            Declaration constructor = fixture.units.get("R.java").syntax().declarations().stream()
                    .filter(declaration -> declaration.kind() == DeclarationKind.CONSTRUCTOR).findFirst().orElseThrow();
            assertThat(fixture.slice(occurrence)).isEqualTo("validate(value)");
            assertThat(occurrence.source().executionScope().kind()).isEqualTo(ScopeKind.CONSTRUCTOR);
            assertThat(occurrence.source().containerSymbolId()).contains(constructor.symbolId());
            assertThat(constructor.range().contains(occurrence.source().range())).isTrue();
            assertThat(constructor.range().contains(occurrence.source().executionScope().range())).isTrue();
        }
    }

    @Test void generated_default_constructors_have_no_fabricated_source_endpoint() throws Exception {
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary,
                Map.of("A.java", "class A { A make() { return new A(); } }"))) {
            Fact creation = fixture.extract().stream().filter(fact -> fact.kind() == Kind.CONSTRUCT).findFirst().orElseThrow();
            assertThat(creation.resolutionState()).isEqualTo(ResolutionState.UNRESOLVED);
            assertThat(creation.unresolvedReason()).contains(Reason.NO_WRITTEN_DECLARATION);
            assertThat(creation.target()).isEmpty();
        }
    }

    @Test void generic_override_method_reference_and_explicit_constructor_bind_written_declarations() throws Exception {
        String source = """
                interface Parent<T> { T convert(T value); }
                class Child implements Parent<String> {
                    Child() {}
                    public String convert(String value) { return value; }
                    void run() {
                        java.util.function.Function<String,String> function = this::convert;
                        Child child = new Child();
                    }
                }
                """;
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Child.java", source))) {
            List<Fact> facts = fixture.extract();
            assertThat(facts).anySatisfy(fact -> {
                assertThat(fact.kind()).isEqualTo(Kind.OVERRIDES);
                assertSourceTarget(fact, "Parent.convert(T)");
            });
            assertThat(facts).anySatisfy(fact -> {
                assertThat(fact.kind()).isEqualTo(Kind.METHOD_REFERENCE);
                assertThat(fixture.slice(fact)).isEqualTo("this::convert");
                assertSourceTarget(fact, "Child.convert(java.lang.String)");
            });
            assertThat(facts).anySatisfy(fact -> {
                assertThat(fact.kind()).isEqualTo(Kind.CONSTRUCT);
                assertSourceTarget(fact, "Child.Child()");
            });
        }
    }

    @Test void ambiguous_overload_is_not_bound_to_an_arbitrary_same_arity_method() throws Exception {
        String source = """
                class A {
                    void pick(String value) {}
                    void pick(Integer value) {}
                    void run() { pick(null); }
                }
                """;
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("A.java", source))) {
            Fact ambiguous = call(fixture, fixture.extract(), "pick(null)");
            assertThat(ambiguous.resolutionState()).isEqualTo(ResolutionState.UNRESOLVED);
            assertThat(ambiguous.unresolvedReason()).contains(Reason.AMBIGUOUS_TARGET);
            assertThat(ambiguous.target()).isEmpty();
        }
    }

    private static Fact call(RelationExtractionFixture fixture, List<Fact> facts, String spelling) {
        return facts.stream().filter(fact -> fact.kind() == Kind.CALL && fixture.slice(fact).equals(spelling)).findFirst().orElseThrow();
    }

    private static void assertSourceTarget(Fact fact, String signature) {
        assertThat(fact.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
        assertThat(fact.target().orElseThrow().source().orElseThrow().qualifiedSignature()).contains(signature);
        assertThat(fact.target().orElseThrow().external()).isEmpty();
    }
}
