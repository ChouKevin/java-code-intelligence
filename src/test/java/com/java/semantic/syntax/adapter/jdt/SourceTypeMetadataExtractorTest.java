package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.callgraph.application.RepositorySyntaxIndex;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceRecordComponentMetadata;
import com.java.semantic.syntax.domain.CompositeTypeReference;
import com.java.semantic.syntax.domain.CompositeTypeReference.CompositeKind;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.InferredTypeReference;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.PrimitiveTypeReference;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SqlSourceKind;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.tuple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 型別 metadata 與 MyBatis SQL 抽取規則 */
class SourceTypeMetadataExtractorTest {

    private final List<SourceTypeMetadata> classes = SyntaxFixtures.extractSyntaxFixture().sourceTypes();

    private final List<SourceTypeMetadata> multiModuleClasses = SyntaxFixtures.extractMultiModuleFixture().sourceTypes();

    private final List<SourceTypeMetadata> evidenceClasses = SyntaxFixtures.extract(
            Path.of("src/test/resources/fixtures/syntax-evidence")).sourceTypes();

    @Test
    void should_extract_enum_constants_and_record_components_as_distinct_source_members(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("value-members");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("ValueMembers.java"), """
                package com.example;

                import java.util.List;

                enum ValueMode {
                    @Deprecated CARD,
                    CASH
                }

                record ValueRecord(@Deprecated List<String> methods, String reference) {
                }
                """);

        List<SourceTypeMetadata> metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes();
        SourceTypeMetadata valueMode = metadata.stream()
                .filter(candidate -> candidate.declaration().identity().fullyQualifiedName().equals("com.example.ValueMode"))
                .findFirst()
                .orElseThrow();
        SourceTypeMetadata valueRecord = metadata.stream()
                .filter(candidate -> candidate.declaration().identity().fullyQualifiedName().equals("com.example.ValueRecord"))
                .findFirst()
                .orElseThrow();

        assertThat(valueMode.members().enumConstants()).extracting(constant -> constant.name())
                .containsExactly("CARD", "CASH");
        assertThat(valueMode.members().enumConstants().getFirst().annotationEvidence())
                .extracting(AnnotationEvidence::writtenName)
                .containsExactly("Deprecated");
        assertThat(valueRecord.members().recordComponents()).extracting(component -> component.name())
                .containsExactly("methods", "reference");
        assertThat(valueRecord.members().fields()).isEmpty();
        assertThat(valueRecord.members().recordComponents().getFirst().type()).isEqualTo("List<String>");
        assertThat(valueRecord.members().recordComponents().getFirst().typeReference().resolvedTypeName())
                .contains("java.util.List");
        assertThat(valueRecord.members().recordComponents().getFirst().annotationEvidence())
                .extracting(AnnotationEvidence::writtenName)
                .containsExactly("Deprecated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeReferenceForms")
    void should_extract_each_supported_jdt_type_form_as_explicit_evidence(
            String writtenType,
            Class<? extends TypeReference> expectedType,
            @TempDir Path tempDir) throws IOException {
        ParsedSource parsed = parseTypeReferenceFixture(tempDir);
        Type type = typeOf(parsed, writtenType);

        TypeReference reference = SourceTypeMetadataExtractor.typeReferenceOf(type);

        assertThat(reference).isInstanceOf(expectedType);
        assertThat(reference.writtenType()).isEqualTo(writtenType);
        assertEvidenceShape(writtenType, reference);
    }

    private static Stream<Arguments> typeReferenceForms() {
        return Stream.of(
                Arguments.of("Order", NamedTypeReference.class),
                Arguments.of("int", PrimitiveTypeReference.class),
                Arguments.of("List<Order>", ParameterizedTypeReference.class),
                Arguments.of("Order[][]", ArrayTypeReference.class),
                Arguments.of("? extends Order", WildcardTypeReference.class),
                Arguments.of("T", TypeVariableReference.class),
                Arguments.of("IllegalArgumentException|IllegalStateException", CompositeTypeReference.class),
                Arguments.of("Order & Runnable", CompositeTypeReference.class),
                Arguments.of("var", InferredTypeReference.class));
    }

    @Test
    void should_project_parameterized_nominal_hierarchy_to_its_raw_repository_type(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("repository-hierarchy");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("RepositoryHierarchy.java"), """
                package com.example;

                interface JpaRepository<T, ID> {
                }

                class Order {
                }

                interface OrderRepository extends JpaRepository<Order, Long> {
                }
                """);

        SourceTypeMetadata metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes().stream()
                .filter(candidate -> candidate.declaration().identity().fullyQualifiedName().equals("com.example.OrderRepository"))
                .findFirst()
                .orElseThrow();

        assertThat(metadata.relationships().extendedTypes()).singleElement().satisfies(reference -> {
            assertThat(reference).isInstanceOf(ParameterizedTypeReference.class);
            assertThat(((ParameterizedTypeReference) reference).simpleTypeName()).isEqualTo("JpaRepository");
        });
    }

    @Test
    void should_reject_a_non_nominal_type_when_a_hierarchy_requires_nominal_evidence(@TempDir Path tempDir)
            throws IOException {
        ParsedSource parsed = parseTypeReferenceFixture(tempDir);
        assertThatThrownBy(() -> SourceTypeMetadataExtractor.nominalTypeReferenceOf(
                typeOf(parsed, "Order[][]")))
                .isInstanceOf(UnsupportedTypeFormException.class)
                .hasMessageContaining("ArrayType");
    }

    @Test
    void should_classify_interface_superinterfaces_as_extended_types(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("interface-inheritance");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Inheritance.java"), """
                package com.example;

                interface Parent {
                }

                interface Child extends Parent {
                }
                """);

        List<SourceTypeMetadata> metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes();
        SourceTypeMetadata child = metadata.stream()
                .filter(candidate -> candidate.declaration().identity().fullyQualifiedName().equals("com.example.Child"))
                .findFirst()
                .orElseThrow();

        assertThat(child.relationships().implementedTypes()).isEmpty();
        assertThat(child.relationships().extendedTypes()).extracting(reference -> reference.simpleTypeName())
                .containsExactly("Parent");
        assertThat(child.relationships().implementedTypes()).isEmpty();
        assertThat(child.relationships().extendedTypes())
                .extracting(TypeReference::resolvedTypeName)
                .containsExactly(java.util.Optional.of("com.example.Parent"));
    }

    @Test
    void should_capture_full_method_declarations_utf16_name_positions_and_declaration_flags(@TempDir Path tempDir)
            throws java.io.IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        java.nio.file.Files.createDirectories(sourceRoot);
        String source = """
                package com.example;

                abstract class FlagFixture {
                    FlagFixture() {
                    }

                    private void privateMethod() {
                    }

                    static void staticMethod() {
                    }

                    final void finalMethod() {
                    }

                    /** 可由 agent 回讀的完整宣告 */
                    void /* 😀 */ open(String text) {
                        String emoji = "😀";
                    }

                    abstract void abstractMethod();

                    native void nativeMethod();

                    void unresolved(MissingDependency dependency) {
                    }
                }
                """;
        java.nio.file.Files.writeString(sourceRoot.resolve("FlagFixture.java"), source);

        SourceTypeMetadata metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes().getFirst();
        SourceMethodMetadata open = methodOf(List.of(metadata), "com.example.FlagFixture", "open");
        SourceMethodMetadata constructor = metadata.members().methods().stream()
                .filter(SourceMethodMetadata::executableDeclaration)
                .filter(method -> "FlagFixture".equals(method.name()))
                .findFirst()
                .orElseThrow();
        SourceMethodMetadata abstractMethod = methodOf(List.of(metadata), "com.example.FlagFixture", "abstractMethod");
        SourceMethodMetadata nativeMethod = methodOf(List.of(metadata), "com.example.FlagFixture", "nativeMethod");
        SourceMethodMetadata unresolved = methodOf(List.of(metadata), "com.example.FlagFixture", "unresolved");

        assertThat(metadata.declaration().identity().sourceFile()).isEqualTo("src/main/java/com/example/FlagFixture.java");
        assertThat(open.declarationLocation().sourceFile()).isEqualTo("src/main/java/com/example/FlagFixture.java");
        assertThat(open.declarationLocation().range()).isEqualTo(new SyntaxRange(
                new SyntaxPosition(15, 4), new SyntaxPosition(18, 5)));
        assertThat(segmentOf(source, open.declarationLocation()))
                .startsWith("/** 可由 agent 回讀的完整宣告 */")
                .endsWith("}");
        assertThat(open.namePosition()).isEqualTo(new SyntaxPosition(16, source.lines().toList().get(16).indexOf("open")));
        assertThat(open.analysisTarget().status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(open.analysisTarget().target()).get().satisfies(target -> {
            assertThat(target.sourceFile()).isEqualTo("src/main/java/com/example/FlagFixture.java");
            assertThat(target.parameterTypes()).containsExactly("java.lang.String");
        });
        assertThat(open.executableDeclaration()).isTrue();
        assertThat(open.overridableDeclaration()).isTrue();
        assertThat(constructor.overridableDeclaration()).isFalse();
        assertThat(metadata.members().methods().stream().filter(method -> method.name().endsWith("Method"))
                .filter(SourceMethodMetadata::executableDeclaration))
                .allSatisfy(method -> assertThat(method.overridableDeclaration()).isFalse());
        assertThat(abstractMethod.executableDeclaration()).isFalse();
        assertThat(abstractMethod.abstractDeclaration()).isTrue();
        assertThat(abstractMethod.overridableDeclaration()).isTrue();
        assertThat(nativeMethod.executableDeclaration()).isFalse();
        assertThat(nativeMethod.abstractDeclaration()).isFalse();

        RepositorySyntaxIndex index = new RepositorySyntaxIndex(
                "orders", new RepositorySyntax(List.of(), List.of(metadata)));
        MethodTarget target = open.analysisTarget().target().orElseThrow();
        assertThat(index.method(target)).contains(open);
        assertThat(index.method(target.sourceFile(), open.declarationLocation().range())).contains(open);
        assertThat(index.method("src/main/java/com/example/Other.java", open.declarationLocation().range())).isEmpty();
        assertThat(index.method(target.sourceFile(), new SyntaxRange(
                new SyntaxPosition(open.declarationLocation().range().start().line(), open.declarationLocation().range().start().character() + 1),
                open.declarationLocation().range().end()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        "src/main/java/com/example/Other.java"),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("other.example", target.className()),
                        target.sourceFile()),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), "Other"),
                        target.sourceFile()),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        target.sourceFile()),
                "other",
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        target.sourceFile()),
                target.methodName(),
                List.of("int")))).isEmpty();
        assertThat(unresolved.analysisTarget().status()).isEqualTo(AnalysisTargetStatus.UNRESOLVED);
        assertThat(index.method(target.sourceFile(), unresolved.declarationLocation().range())).isEmpty();
    }

    @Test
    void should_mark_methods_non_overridable_only_when_their_enclosing_declaration_cannot_be_subclassed(
            @TempDir Path tempDir) throws java.io.IOException {
        Path repositoryRoot = tempDir.resolve("overridable-declarations");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OverridableDeclarations.java"), """
                package com.example;

                final class FinalFixture {
                    void open() {
                    }
                }

                record RecordFixture(String value) {
                    void open() {
                    }
                }

                class OrdinaryFixture {
                    void open() {
                    }
                }

                enum EnumFixture {
                    SPECIAL {
                        @Override
                        void open() {
                        }
                    },
                    STANDARD;

                    void open() {
                    }
                }
                """);

        List<SourceTypeMetadata> metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes();

        assertThat(methodOf(metadata, "com.example.FinalFixture", "open").overridableDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.RecordFixture", "open").overridableDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.OrdinaryFixture", "open").overridableDeclaration()).isTrue();
        assertThat(methodOf(metadata, "com.example.EnumFixture", "open").overridableDeclaration()).isTrue();
    }

    @Test
    void should_mark_bodyless_overridable_interface_methods_as_abstract_declarations(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("interface-declarations");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("InterfaceDeclarations.java"), """
                package com.example;

                interface Port {
                    void handle();

                    default void defaultHandle() {
                    }

                    static void staticHandle() {
                    }

                    private void privateHandle() {
                    }
                }

                abstract class AbstractPort {
                    abstract void explicitHandle();

                    native void nativeHandle();
                }
                """);

        List<SourceTypeMetadata> metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).sourceTypes();

        assertThat(methodOf(metadata, "com.example.Port", "handle").abstractDeclaration()).isTrue();
        assertThat(methodOf(metadata, "com.example.Port", "defaultHandle").abstractDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.Port", "staticHandle").abstractDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.Port", "privateHandle").abstractDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.AbstractPort", "explicitHandle").abstractDeclaration()).isTrue();
        assertThat(methodOf(metadata, "com.example.AbstractPort", "nativeHandle").abstractDeclaration()).isFalse();
    }

    // --- 呼叫圖語法證據 ---

    @Test
    void should_extract_exact_source_qualifier_invocation_and_resolved_type_evidence_in_one_snapshot() {
        SourceTypeMetadata service = classOf(evidenceClasses, "com.example.evidence.FastOrderService");
        SourceTypeMetadata coordinator = classOf(evidenceClasses, "com.example.evidence.OrderCoordinator");
        SourceFieldMetadata mapper = coordinator.members().fields().stream()
                .filter(field -> "mapper".equals(field.name()))
                .findFirst()
                .orElseThrow();
        SourceFieldMetadata unresolved = coordinator.members().fields().stream()
                .filter(field -> "unresolved".equals(field.name()))
                .findFirst()
                .orElseThrow();
        SourceFieldMetadata libraryValues = coordinator.members().fields().stream()
                .filter(field -> "libraryValues".equals(field.name()))
                .findFirst()
                .orElseThrow();
        SourceMethodMetadata process = methodOf(evidenceClasses,
                "com.example.evidence.OrderCoordinator", "process");

        assertThat(service.frameworkFacts().primary()).isTrue();
        assertThat(service.frameworkFacts().beanQualifiers()).containsExactly("fastOrderService");
        assertThat(service.declaration().declarationLocation().range().start().line()).isEqualTo(8);
        assertThat(service.declaration().declarationLocation().range().start().character()).isZero();

        assertThat(mapper.type()).isEqualTo("OrderMapper");
        assertThat(mapper.annotationEvidence()).extracting(AnnotationEvidence::writtenName)
                .containsExactly("Qualifier");
        assertThat(mapper.annotationEvidence()).singleElement().satisfies(annotation -> {
            assertThat(annotation.writtenName()).isEqualTo("Qualifier");
            assertThat(annotation.resolvedType()).get().satisfies(type -> {
                assertThat(type.packageName()).isEqualTo("com.example.evidence");
                assertThat(type.className()).isEqualTo("Qualifier");
            });
        });
        assertThat(mapper.qualifier()).isEqualTo("vaultMapper");
        assertThat(mapper.typeReference().writtenType()).isEqualTo("OrderMapper");
        assertThat(mapper.typeReference().resolvedTypeName()).contains("com.example.evidence.OrderMapper");
        assertThat(mapper.typeReference().sourceDefined()).isTrue();
        assertThat(unresolved.typeReference().writtenType()).isEqualTo("MissingDependency");
        assertThat(unresolved.typeReference().resolvedTypeName())
                .as("JDT recovery binding is not proven identity and must not escape as a guessed name")
                .isEmpty();
        assertThat(unresolved.typeReference().sourceDefined()).isFalse();
        assertThat(libraryValues.typeReference().resolvedTypeName()).contains("java.util.List");
        assertThat(libraryValues.typeReference().sourceDefined()).isFalse();

        assertThat(process.paramTypes()).containsExactly("OrderLine");
        assertThat(process.parameterTypeReferences()).singleElement().satisfies(parameter -> {
            assertThat(parameter.writtenType()).isEqualTo("OrderLine");
            assertThat(parameter.resolvedTypeName()).contains("com.example.evidence.OrderLine");
            assertThat(parameter.sourceDefined()).isTrue();
        });
        assertThat(process.returnType()).get().satisfies(returnType -> {
            assertThat(returnType.writtenType()).isEqualTo("Receipt<OrderLine>");
            assertThat(returnType).isInstanceOf(ParameterizedTypeReference.class);
            ParameterizedTypeReference parameterized = (ParameterizedTypeReference) returnType;
            assertThat(parameterized.resolvedTypeName()).contains("com.example.evidence.Receipt");
            assertThat(parameterized.typeArguments()).singleElement().satisfies(argument ->
                    assertThat(argument.resolvedTypeName()).contains("com.example.evidence.OrderLine"));
        });
        assertThat(process.declarationLocation().range().start().line()).isEqualTo(22);
        assertThat(process.declarationLocation().range().start().character()).isEqualTo(4);

        assertThat(process.invocations()).hasSize(9);
        assertThat(process.invocations())
                .extracting(SyntaxInvocation::kind, SyntaxInvocation::expression)
                .containsExactly(
                        tuple(InvocationKind.METHOD, "mapper.save(order)"),
                        tuple(InvocationKind.METHOD, "mapper.save(order)"),
                        tuple(InvocationKind.CONSTRUCTOR, "new OrderLine()"),
                        tuple(InvocationKind.METHOD, "mapper.save(order)"),
                        tuple(InvocationKind.METHOD_REFERENCE, "mapper::save"),
                        tuple(InvocationKind.METHOD_REFERENCE, "OrderLine::new"),
                        tuple(InvocationKind.METHOD_REFERENCE, "OrderLine::value"),
                        tuple(InvocationKind.STATIC_IMPORT, "record(\"saved\")"),
                        tuple(InvocationKind.METHOD, "recoveredRecord(\"unresolved\")"));

        List<SyntaxInvocation> repeatedSaves = process.invocations().stream()
                .filter(invocation -> "mapper.save(order)".equals(invocation.expression()))
                .toList();
        assertThat(repeatedSaves).hasSize(3);
        assertThat(repeatedSaves)
                .extracting(SyntaxInvocation::range)
                .containsExactly(
                        syntaxRange(23, 37, 23, 55),
                        syntaxRange(24, 38, 24, 56),
                        syntaxRange(26, 32, 26, 50));

        SyntaxInvocation save = invocationOf(process, "mapper.save(order)");
        assertThat(save.receiver()).isEqualTo("mapper");
        assertThat(save.receiverDeclaration()).isEqualTo("com.example.evidence.OrderMapper");
        assertThat(save.qualifier()).isEqualTo("vaultMapper");
        assertThat(save.resolvedTarget()).contains(new InvocationTarget(
                "com.example.evidence", "OrderMapper", "save", List.of("com.example.evidence.OrderLine")));
        assertThat(save.range().start().line()).isEqualTo(23);
        assertThat(save.range().start().character()).isEqualTo(37);
        assertThat(save.range().end().character()).isEqualTo(55);

        SyntaxInvocation constructor = invocationOf(process, "new OrderLine()");
        assertThat(constructor.receiver()).isEqualTo("OrderLine");
        assertThat(constructor.receiverDeclaration()).isEqualTo("com.example.evidence.OrderLine");
        assertThat(constructor.resolvedTarget()).contains(new InvocationTarget(
                "com.example.evidence", "OrderLine", "OrderLine", List.of("java.lang.String")));
        assertThat(constructor.range()).isEqualTo(syntaxRange(25, 28, 25, 43));

        SyntaxInvocation creationReference = invocationOf(process, "OrderLine::new");
        assertThat(creationReference.receiver()).isEqualTo("OrderLine");
        assertThat(creationReference.receiverDeclaration()).isEqualTo("com.example.evidence.OrderLine");
        assertThat(creationReference.range()).isEqualTo(syntaxRange(28, 50, 28, 64));

        SyntaxInvocation typeReference = invocationOf(process, "OrderLine::value");
        assertThat(typeReference.receiver()).isEqualTo("OrderLine");
        assertThat(typeReference.receiverDeclaration()).isEqualTo("com.example.evidence.OrderLine");
        assertThat(typeReference.range()).isEqualTo(syntaxRange(29, 52, 29, 68));

        SyntaxInvocation staticImport = invocationOf(process, "record(\"saved\")");
        assertThat(staticImport.receiver()).isEqualTo("");
        assertThat(staticImport.receiverDeclaration()).isEqualTo("");
        assertThat(staticImport.qualifier()).isEqualTo("");
        assertThat(staticImport.range()).isEqualTo(syntaxRange(30, 8, 30, 23));

        SourceMethodMetadata qualifiedSuper = methodOf(evidenceClasses,
                "com.example.evidence.OuterEvidence.Inner", "qualifiedSuperReference");
        assertThat(qualifiedSuper.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.expression()).isEqualTo("OuterEvidence.super::inherited");
            assertThat(invocation.receiver()).isEqualTo("OuterEvidence.super");
            assertThat(invocation.receiverDeclaration()).isEqualTo("com.example.evidence.ParentEvidence");
            assertThat(invocation.range()).isEqualTo(syntaxRange(48, 19, 48, 49));
        });
    }

    @Test
    void should_extract_binding_proven_nested_policy_identities_and_wildcard_bounds() {
        SourceMethodMetadata inspect = methodOf(evidenceClasses,
                "com.example.evidence.TypeEvidenceFixture", "inspect");

        assertThat(inspect.annotationEvidence()).singleElement().satisfies(annotation ->
                assertThat(annotation.resolvedType()).contains(
                        new JavaTypeIdentity(
                                "com.example.evidence", "PolicyMarker.Nested")));
        assertThat(inspect.parameterTypeReferences()).singleElement().satisfies(parameter -> {
            ParameterizedTypeReference parameterized = (ParameterizedTypeReference) parameter;
            WildcardTypeReference wildcard = (WildcardTypeReference) parameterized.typeArguments().getFirst();
            assertThat(wildcard.upperBound()).flatMap(TypeReference::resolvedTypeName)
                    .contains("com.example.evidence.SecretDto");
        });
        assertThat(inspect.returnType()).get().satisfies(returnType -> {
            ParameterizedTypeReference parameterized = (ParameterizedTypeReference) returnType;
            WildcardTypeReference wildcard = (WildcardTypeReference) parameterized.typeArguments().getFirst();
            assertThat(wildcard.lowerBound()).flatMap(TypeReference::resolvedTypeName)
                    .contains("com.example.evidence.SecretDto");
        });
        assertThat(inspect.invocations())
                .extracting(SyntaxInvocation::expression, SyntaxInvocation::resolvedTarget)
                .contains(
                        tuple("accept(values)", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "TypeEvidenceFixture", "accept", List.of("List")))),
                        tuple("new OuterIdentity.Inner()", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "OuterIdentity.Inner", "Inner", List.of()))),
                        tuple("created::overloaded", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "OuterIdentity.Inner", "overloaded", List.of("List")))),
                        tuple("varargs(\"policy\")", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "TypeEvidenceFixture", "varargs", List.of("String[]")))),
                        tuple("record(\"policy\")", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "Audit", "record", List.of("String")))));
    }

    @Test
    void should_extract_binding_proven_body_type_identities_from_non_invocation_sites() {
        SourceMethodMetadata inspect = methodOf(evidenceClasses,
                "com.example.evidence.BodyTypeEvidenceFixture", "inspect");

        assertThat(inspect.bodyTypeReferences()).contains(
                new JavaTypeIdentity("com.example.evidence", "ForbiddenLocal"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenCast"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenInstanceof"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenClassLiteral"),
                new JavaTypeIdentity("com.example.evidence", "SecretHolder"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenFieldValue"),
                new JavaTypeIdentity("com.example.evidence", "GenericSecretHolder"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenGenericFieldValue"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenBareFieldValue"),
                new JavaTypeIdentity("com.example.evidence", "ForbiddenAnnotationMember"));
    }

    @Test
    void should_extract_type_variable_binding_bounds_for_method_field_and_self_reference() {
        SourceMethodMetadata load = methodOf(evidenceClasses,
                "com.example.evidence.GenericBoundFixture", "load");
        TypeReference parameter = load.parameterTypeReferences().getFirst();
        TypeReference returned = load.returnType().orElseThrow();
        SourceFieldMetadata field = classOf(evidenceClasses, "com.example.evidence.BoundedBox").members().fields().stream()
                .filter(value -> "value".equals(value.name()))
                .findFirst()
                .orElseThrow();
        SourceMethodMetadata echo = methodOf(evidenceClasses,
                "com.example.evidence.GenericBoundFixture", "echo");

        TypeVariableReference parameterVariable = (TypeVariableReference) parameter;
        TypeVariableReference returnedVariable = (TypeVariableReference) returned;
        TypeVariableReference fieldVariable = (TypeVariableReference) field.typeReference();
        assertThat(parameterVariable.upperBounds()).extracting(TypeReference::resolvedTypeName)
                .containsExactly(
                        java.util.Optional.of("com.example.evidence.ForbiddenDto"),
                        java.util.Optional.of("com.example.evidence.ForbiddenMarker"));
        assertThat(returnedVariable.upperBounds()).extracting(TypeReference::resolvedTypeName)
                .containsExactly(
                        java.util.Optional.of("com.example.evidence.ForbiddenDto"),
                        java.util.Optional.of("com.example.evidence.ForbiddenMarker"));
        assertThat(fieldVariable.upperBounds()).singleElement()
                .extracting(TypeReference::resolvedTypeName)
                .isEqualTo(java.util.Optional.of("com.example.evidence.ForbiddenDto"));
        assertThat(echo.parameterTypeReferences()).singleElement().satisfies(selfReference -> {
            TypeVariableReference selfVariable = (TypeVariableReference) selfReference;
            assertThat(selfVariable.upperBounds()).singleElement().satisfies(comparable -> {
                ParameterizedTypeReference parameterized = (ParameterizedTypeReference) comparable;
                assertThat(parameterized.resolvedTypeName()).contains("java.lang.Comparable");
                assertThat(parameterized.typeArguments()).singleElement().satisfies(nested ->
                        assertThat(((TypeVariableReference) nested).upperBounds()).hasSize(0));
            });
        });
    }

    // --- MyBatis SQL ---

    @Test
    void should_prefer_annotation_sql_over_xml_when_both_declare_the_same_statement() {
        SourceMethodMetadata method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "findByOrderNo");

        assertThat(method.sqlSource()).isEqualTo(SqlSourceKind.ANNOTATION);
        assertThat(method.annotationSqlLocation()).isPresent();
    }

    @Test
    void should_fall_back_to_xml_when_a_mapper_method_has_no_sql_annotation() {
        SourceMethodMetadata method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "xmlOnly");

        assertThat(method.sqlSource()).isEqualTo(SqlSourceKind.MAPPER_XML);
        assertThat(method.annotationSqlLocation()).isEmpty();
    }

    @Test
    void should_read_the_value_attribute_form_when_a_select_is_written_with_a_named_attribute() {
        SourceMethodMetadata method = methodOf(classes, "com.example.syntax.AccountMapper", "findByCode");

        assertThat(method.annotationSqlLocation()).isPresent().get()
                .satisfies(location -> {
                    assertThat(location.sourceFile())
                            .isEqualTo("src/main/java/com/example/syntax/AccountMapper.java");
                    assertThat(segmentOf(fixtureSource(location.sourceFile()), location))
                            .isEqualTo("@Select(value = \"SELECT * FROM accounts WHERE code = #{code}\")");
                });
    }

    @Test
    void should_join_the_elements_when_a_select_is_written_as_a_string_array() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "findByStatus").annotationSqlLocation())
                .isPresent();
    }

    @Test
    void should_include_the_enclosing_type_when_a_nested_mapper_is_qualified() {
        assertThat(classes)
                .as("舊分析器產出 com.example.syntax.Nested，永遠對不上 XML namespace")
                .anyMatch(metadata -> "com.example.syntax.AccountMapper.Nested"
                        .equals(metadata.declaration().identity().fullyQualifiedName()));
    }

    @Test
    void should_leave_sql_source_unset_when_a_method_has_no_sql_at_all() {
        SourceMethodMetadata method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.annotationSqlLocation()).isEmpty();
        assertThat(method.sqlSource()).isNull();
    }

    // --- 型別形狀 ---

    @Test
    void should_classify_records_and_enums_when_a_file_declares_several_type_kinds() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").declaration().kind()).isEqualTo(SourceTypeKind.RECORD);
        assertThat(classOf(classes, "com.example.syntax.AccountStatus").declaration().kind()).isEqualTo(SourceTypeKind.ENUM);
        assertThat(classOf(classes, "com.example.syntax.AccountMapper").declaration().kind()).isEqualTo(SourceTypeKind.INTERFACE);
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").declaration().kind()).isEqualTo(SourceTypeKind.CLASS);
    }

    @Test
    void should_expose_record_components_separately_when_a_record_is_scanned() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").members().fields()).isEmpty();
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").members().recordComponents())
                .extracting(SourceRecordComponentMetadata::name, SourceRecordComponentMetadata::type)
                .containsExactly(
                        tuple("accountNo", "String"),
                        tuple("total", "long"));
    }

    @Test
    void should_detect_fluent_accessors_when_the_class_declares_accessors_fluent_true() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").members().fluentSetters())
                .as("舊分析器比對 pretty print 後的字串是否等於 true")
                .isTrue();
    }

    @Test
    void should_apply_explicit_and_default_accessors_chain_values() {
        assertThat(classOf(classes, "com.example.syntax.ExplicitChainedShapes").members().chainedAccessors())
                .isTrue();
        assertThat(classOf(classes, "com.example.syntax.ExplicitNonChainedShapes").members().chainedAccessors())
                .isFalse();
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").members().chainedAccessors())
                .as("Lombok defaults chain to fluent when chain is absent")
                .isTrue();
    }

    @Test
    void should_read_every_profile_when_the_profile_annotation_declares_an_array() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").frameworkFacts().profiles())
                .containsExactly("dev", "uat");
    }

    @Test
    void should_keep_the_short_annotation_name_when_the_source_writes_it_short() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").frameworkFacts().annotations())
                .extracting(AnnotationEvidence::writtenName)
                .contains("Service", "Profile", "Accessors");
    }

    @Test
    void should_keep_the_long_annotation_name_when_the_source_writes_it_fully_qualified() {
        assertThat(classOf(classes, "com.example.syntax.FullyQualifiedEndpoint").frameworkFacts().annotations())
                .extracting(AnnotationEvidence::writtenName)
                .as("metadata 保留寫法原貌；改用 simpleNameOf 縮短時這個斷言才會變紅")
                .contains("org.springframework.stereotype.Service",
                        "org.springframework.web.bind.annotation.RequestMapping")
                .doesNotContain("Service", "RequestMapping");
    }

    @Test
    void should_keep_the_long_method_annotation_name_when_a_mapping_is_written_fully_qualified() {
        assertThat(methodOf(classes, "com.example.syntax.FullyQualifiedEndpoint", "ping").annotationEvidence())
                .extracting(AnnotationEvidence::writtenName)
                .containsExactly("org.springframework.web.bind.annotation.GetMapping");
    }

    @Test
    void should_record_one_based_line_numbers_when_a_method_is_scanned() {
        SourceMethodMetadata method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.declarationLocation().range().start().line()).isGreaterThanOrEqualTo(0);
        assertThat(method.declarationLocation().range().end().line())
                .isGreaterThanOrEqualTo(method.declarationLocation().range().start().line());
    }

    @Test
    void should_simplify_parameter_types_when_a_method_declares_qualified_or_generic_parameters() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "updateStatus").paramTypes())
                .containsExactly("Long", "String");
    }

    @Test
    void should_include_every_module_when_the_repository_is_a_maven_aggregator() {
        assertThat(multiModuleClasses)
                .extracting(metadata -> metadata.declaration().identity().fullyQualifiedName())
                .contains("com.example.api.OrderMessageListener",
                        "com.example.service.OrderApplicationService",
                        "com.example.persistence.OrderMapper");
    }

    @Test
    void should_extract_a_single_module_repository_when_it_carries_no_build_file() {
        RepositorySyntax syntax = SyntaxFixtures.extract(SyntaxFixtures.SPRING_BASIC);

        assertThat(syntax.entryPoints())
                .as("JDT Core 的 ASTParser 不讀 pom；source root 由目錄結構決定")
                .flatExtracting(entry -> entry.methods().stream().map(EntryPointMethod::name).toList())
                .containsExactly("getBasic");
        assertThat(methodOf(syntax.sourceTypes(), "com.example.basic.BasicRepository", "findById")
                .sqlSource()).isEqualTo(SqlSourceKind.MAPPER_XML);
    }

    private SourceTypeMetadata classOf(List<SourceTypeMetadata> source, String fullyQualifiedName) {
        return source.stream()
                .filter(metadata -> fullyQualifiedName.equals(metadata.declaration().identity().fullyQualifiedName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class metadata " + fullyQualifiedName));
    }

    private SourceMethodMetadata methodOf(List<SourceTypeMetadata> source, String fullyQualifiedName, String methodName) {
        return classOf(source, fullyQualifiedName).members().methods().stream()
                .filter(method -> methodName.equals(method.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + fullyQualifiedName + "#" + methodName));
    }

    private SyntaxInvocation invocationOf(SourceMethodMetadata method, String expression) {
        return method.invocations().stream()
                .filter(invocation -> expression.equals(invocation.expression()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no invocation " + expression));
    }

    private ParsedSource parseTypeReferenceFixture(Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("type-reference-forms");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Path sourceFile = sourceRoot.resolve("TypeReferenceForms.java");
        String sourceText = """
                package com.example;

                import java.util.List;

                class Order implements Runnable {
                    @Override
                    public void run() {
                    }
                }

                class TypeReferenceForms<T extends Order & Runnable> {
                    void nominal(Order value) {
                    }

                    void primitive(int value) {
                    }

                    void parameterized(List<Order> values) {
                    }

                    void array(Order[][] values) {
                    }

                    void wildcard(List<? extends Order> values) {
                    }

                    T typeVariable(T value) {
                        return value;
                    }

                    void composite(Object value) {
                        Object narrowed = (Order & Runnable) value;
                        try {
                        } catch (IllegalArgumentException | IllegalStateException exception) {
                        }
                    }

                    void inferred() {
                        %s local = new Order();
                    }
                }
                """.formatted("var");
        Files.writeString(sourceFile, sourceText);
        SourceFile source = new SourceFile(sourceFile, sourceRoot, repositoryRoot);
        List<ParsedSource> parsed = new ArrayList<>();
        new JdtAstParser(List.of(sourceRoot)).parse(List.of(source), parsed::add);
        return parsed.getFirst();
    }

    private Type typeOf(ParsedSource parsed, String writtenType) {
        AtomicReference<Type> found = new AtomicReference<>();
        parsed.unit().accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Type type && Objects.isNull(found.get())
                        && writtenType.replace("|", " | ").equals(AstSourceRanges.text(parsed.text(), type))) {
                    found.set(type);
                }
            }
        });
        Type type = found.get();
        if (Objects.isNull(type)) {
            throw new AssertionError("no type " + writtenType);
        }
        return type;
    }

    private void assertEvidenceShape(String writtenType, TypeReference reference) {
        if (reference instanceof NamedTypeReference named) {
            assertThat(named.simpleTypeName()).isEqualTo("Order");
            assertThat(named.resolvedTypeName()).contains("com.example.Order");
            return;
        }
        if (reference instanceof PrimitiveTypeReference primitive) {
            assertThat(primitive.resolvedTypeName()).isEmpty();
            return;
        }
        if (reference instanceof ParameterizedTypeReference parameterized) {
            assertThat(parameterized.rawType().simpleTypeName()).isEqualTo("List");
            assertThat(parameterized.typeArguments()).singleElement().isInstanceOf(NamedTypeReference.class);
            return;
        }
        if (reference instanceof ArrayTypeReference array) {
            assertThat(array.dimensions()).isEqualTo(2);
            assertThat(array.resolvedTypeName()).contains("com.example.Order[][]");
            return;
        }
        if (reference instanceof WildcardTypeReference wildcard) {
            assertThat(wildcard.upperBound()).isPresent();
            assertThat(wildcard.lowerBound()).isEmpty();
            return;
        }
        if (reference instanceof TypeVariableReference variable) {
            assertThat(variable.variableName()).isEqualTo("T");
            assertThat(variable.upperBounds()).hasSize(2);
            return;
        }
        if (reference instanceof CompositeTypeReference composite) {
            CompositeKind expectedKind = writtenType.contains("|")
                    ? CompositeKind.UNION
                    : CompositeKind.INTERSECTION;
            assertThat(composite.kind()).isEqualTo(expectedKind);
            assertThat(composite.alternatives()).hasSize(2);
            return;
        }
        InferredTypeReference inferred = (InferredTypeReference) reference;
        assertThat(inferred.resolvedNamedType()).isEmpty();
    }

    private static String fixtureSource(String sourceFile) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/syntax-extraction").resolve(sourceFile));
        } catch (IOException exception) {
            throw new AssertionError("fixture source cannot be read", exception);
        }
    }

    private static String segmentOf(String source, SourceRange location) {
        int start = offsetOf(source, location.range().start().line(), location.range().start().character());
        int end = offsetOf(source, location.range().end().line(), location.range().end().character());
        return source.substring(start, end);
    }

    private static int offsetOf(String source, int line, int character) {
        int offset = 0;
        for (int currentLine = 0; currentLine < line; currentLine++) {
            offset = source.indexOf('\n', offset) + 1;
        }
        return offset + character;
    }

    private SyntaxRange syntaxRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
