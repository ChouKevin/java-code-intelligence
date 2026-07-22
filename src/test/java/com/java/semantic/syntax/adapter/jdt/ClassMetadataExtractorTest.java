package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.callgraph.application.RepositorySyntaxIndex;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.TypeReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.tuple;

import static org.assertj.core.api.Assertions.assertThat;

/** 型別 metadata 與 MyBatis SQL 抽取規則 */
class ClassMetadataExtractorTest {

    private final List<ClassMetadata> classes = SyntaxFixtures.extractSyntaxFixture().classes();

    private final List<ClassMetadata> multiModuleClasses = SyntaxFixtures.extractMultiModuleFixture().classes();

    private final List<ClassMetadata> evidenceClasses = SyntaxFixtures.extract(
            Path.of("src/test/resources/fixtures/syntax-evidence")).classes();

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

                    void /* 😀 */ open(String text) {
                        String emoji = "😀";
                    }

                    abstract void abstractMethod();

                    void unresolved(MissingDependency dependency) {
                    }
                }
                """;
        java.nio.file.Files.writeString(sourceRoot.resolve("FlagFixture.java"), source);

        ClassMetadata metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).classes().getFirst();
        MethodSignature open = methodOf(List.of(metadata), "com.example.FlagFixture", "open");
        MethodSignature constructor = metadata.methods().stream()
                .filter(MethodSignature::executableDeclaration)
                .filter(method -> "FlagFixture".equals(method.name()))
                .findFirst()
                .orElseThrow();
        MethodSignature abstractMethod = methodOf(List.of(metadata), "com.example.FlagFixture", "abstractMethod");
        MethodSignature unresolved = methodOf(List.of(metadata), "com.example.FlagFixture", "unresolved");

        assertThat(open.source().text()).isEqualTo("""
                void /* 😀 */ open(String text) {
                        String emoji = "😀";
                    }""");
        assertThat(open.range()).isEqualTo(new SyntaxRange(
                new SyntaxPosition(15, 4), new SyntaxPosition(17, 5)));
        assertThat(open.namePosition()).isEqualTo(new SyntaxPosition(15, source.lines().toList().get(15).indexOf("open")));
        assertThat(open.analysisTarget().status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(open.analysisTarget().target()).get().satisfies(target -> {
            assertThat(target.sourceFile()).isEqualTo("src/main/java/com/example/FlagFixture.java");
            assertThat(target.parameterTypes()).containsExactly("java.lang.String");
        });
        assertThat(open.executableDeclaration()).isTrue();
        assertThat(open.overridableDeclaration()).isTrue();
        assertThat(constructor.overridableDeclaration()).isFalse();
        assertThat(metadata.methods().stream().filter(method -> method.name().endsWith("Method"))
                .filter(method -> !"abstractMethod".equals(method.name())))
                .allSatisfy(method -> assertThat(method.overridableDeclaration()).isFalse());
        assertThat(abstractMethod.executableDeclaration()).isFalse();
        assertThat(abstractMethod.overridableDeclaration()).isTrue();

        RepositorySyntaxIndex index = new RepositorySyntaxIndex(
                "orders", new RepositorySyntax(List.of(), List.of(metadata)));
        MethodTarget target = open.analysisTarget().target().orElseThrow();
        assertThat(index.method(target)).contains(open);
        assertThat(index.method(target.sourceFile(), open.range())).contains(open);
        assertThat(index.method("src/main/java/com/example/Other.java", open.range())).isEmpty();
        assertThat(index.method(target.sourceFile(), new SyntaxRange(
                new SyntaxPosition(open.range().start().line(), open.range().start().character() + 1),
                open.range().end()))).isEmpty();
        assertThat(index.method(new MethodTarget("src/main/java/com/example/Other.java", target.packageName(),
                target.className(), target.methodName(), target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(target.sourceFile(), "other.example", target.className(),
                target.methodName(), target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(target.sourceFile(), target.packageName(), "Other",
                target.methodName(), target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(target.sourceFile(), target.packageName(), target.className(), "other",
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(target.sourceFile(), target.packageName(), target.className(),
                target.methodName(), List.of("int")))).isEmpty();
        assertThat(unresolved.analysisTarget().status()).isEqualTo(AnalysisTargetStatus.UNRESOLVED);
        assertThat(index.method(target.sourceFile(), unresolved.range())).isEmpty();
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

        List<ClassMetadata> metadata = new JdtSyntaxExtractionService().extract(repositoryRoot).classes();

        assertThat(methodOf(metadata, "com.example.FinalFixture", "open").overridableDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.RecordFixture", "open").overridableDeclaration()).isFalse();
        assertThat(methodOf(metadata, "com.example.OrdinaryFixture", "open").overridableDeclaration()).isTrue();
        assertThat(methodOf(metadata, "com.example.EnumFixture", "open").overridableDeclaration()).isTrue();
    }

    // --- 呼叫圖語法證據 ---

    @Test
    void should_extract_exact_source_qualifier_invocation_and_resolved_type_evidence_in_one_snapshot() {
        ClassMetadata service = classOf(evidenceClasses, "com.example.evidence.FastOrderService");
        ClassMetadata coordinator = classOf(evidenceClasses, "com.example.evidence.OrderCoordinator");
        FieldInfo mapper = coordinator.fields().stream()
                .filter(field -> "mapper".equals(field.name()))
                .findFirst()
                .orElseThrow();
        FieldInfo unresolved = coordinator.fields().stream()
                .filter(field -> "unresolved".equals(field.name()))
                .findFirst()
                .orElseThrow();
        FieldInfo libraryValues = coordinator.fields().stream()
                .filter(field -> "libraryValues".equals(field.name()))
                .findFirst()
                .orElseThrow();
        MethodSignature process = methodOf(evidenceClasses,
                "com.example.evidence.OrderCoordinator", "process");

        assertThat(service.primary()).isTrue();
        assertThat(service.beanQualifiers()).containsExactly("fastOrderService");
        assertThat(service.source().text())
                .isEqualTo("@Qualifier(\"fastOrderService\")\n@Primary\nclass FastOrderService {\n}");
        assertThat(service.range().start().line()).isEqualTo(8);
        assertThat(service.range().start().character()).isZero();

        assertThat(mapper.type()).isEqualTo("OrderMapper");
        assertThat(mapper.annotations()).containsExactly("Qualifier");
        assertThat(mapper.annotationEvidence()).singleElement().satisfies(annotation -> {
            assertThat(annotation.writtenName()).isEqualTo("Qualifier");
            assertThat(annotation.resolvedType()).get().satisfies(type -> {
                assertThat(type.packageName()).isEqualTo("com.example.evidence");
                assertThat(type.className()).isEqualTo("Qualifier");
            });
        });
        assertThat(mapper.qualifier()).isEqualTo("vaultMapper");
        assertThat(mapper.typeReference().writtenType()).isEqualTo("OrderMapper");
        assertThat(mapper.typeReference().resolvedType()).isEqualTo("com.example.evidence.OrderMapper");
        assertThat(mapper.typeReference().sourceDefined()).isTrue();
        assertThat(unresolved.typeReference().writtenType()).isEqualTo("MissingDependency");
        assertThat(unresolved.typeReference().resolvedType())
                .as("JDT recovery binding is not proven identity and must not escape as a guessed name")
                .isEqualTo("");
        assertThat(unresolved.typeReference().sourceDefined()).isFalse();
        assertThat(libraryValues.typeReference().resolvedType()).isEqualTo("java.util.List");
        assertThat(libraryValues.typeReference().sourceDefined()).isFalse();

        assertThat(process.paramTypes()).containsExactly("OrderLine");
        assertThat(process.parameterTypeReferences()).singleElement().satisfies(parameter -> {
            assertThat(parameter.writtenType()).isEqualTo("OrderLine");
            assertThat(parameter.resolvedType()).isEqualTo("com.example.evidence.OrderLine");
            assertThat(parameter.sourceDefined()).isTrue();
        });
        assertThat(process.returnType()).get().satisfies(returnType -> {
            assertThat(returnType.writtenType()).isEqualTo("Receipt<OrderLine>");
            assertThat(returnType.resolvedType()).isEqualTo("com.example.evidence.Receipt");
            assertThat(returnType.typeArguments()).singleElement().satisfies(argument ->
                    assertThat(argument.resolvedType()).isEqualTo("com.example.evidence.OrderLine"));
        });
        assertThat(process.source().text()).startsWith("Receipt<OrderLine> process(OrderLine order) {")
                .contains("mapper.save(order)", "OrderLine::new", "record(\"saved\")");
        assertThat(process.range().start().line()).isEqualTo(22);
        assertThat(process.range().start().character()).isEqualTo(4);

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

        MethodSignature qualifiedSuper = methodOf(evidenceClasses,
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
        MethodSignature inspect = methodOf(evidenceClasses,
                "com.example.evidence.PolicyIdentityFixture", "inspect");

        assertThat(inspect.annotationEvidence()).singleElement().satisfies(annotation ->
                assertThat(annotation.resolvedType()).contains(
                        new ResolvedTypeIdentity(
                                "com.example.evidence", "PolicyMarker.Nested")));
        assertThat(inspect.parameterTypeReferences()).singleElement().satisfies(parameter -> {
            TypeReference wildcard = parameter.typeArguments().getFirst();
            assertThat(wildcard.upperBounds()).singleElement()
                    .extracting(TypeReference::resolvedType)
                    .isEqualTo("com.example.evidence.SecretDto");
        });
        assertThat(inspect.returnType()).get().satisfies(returnType -> {
            TypeReference wildcard = returnType.typeArguments().getFirst();
            assertThat(wildcard.lowerBounds()).singleElement()
                    .extracting(TypeReference::resolvedType)
                    .isEqualTo("com.example.evidence.SecretDto");
        });
        assertThat(inspect.invocations())
                .extracting(SyntaxInvocation::expression, SyntaxInvocation::resolvedTarget)
                .contains(
                        tuple("accept(values)", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "PolicyIdentityFixture", "accept", List.of("List")))),
                        tuple("new OuterIdentity.Inner()", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "OuterIdentity.Inner", "Inner", List.of()))),
                        tuple("created::overloaded", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "OuterIdentity.Inner", "overloaded", List.of("List")))),
                        tuple("varargs(\"policy\")", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "PolicyIdentityFixture", "varargs", List.of("String[]")))),
                        tuple("record(\"policy\")", java.util.Optional.of(new InvocationTarget(
                                "com.example.evidence", "Audit", "record", List.of("String")))));
    }

    @Test
    void should_extract_binding_proven_body_type_identities_from_non_invocation_sites() {
        MethodSignature inspect = methodOf(evidenceClasses,
                "com.example.evidence.BodyTypeEvidenceFixture", "inspect");

        assertThat(inspect.bodyTypeReferences()).contains(
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenLocal"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenCast"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenInstanceof"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenClassLiteral"),
                new ResolvedTypeIdentity("com.example.evidence", "SecretHolder"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenFieldValue"),
                new ResolvedTypeIdentity("com.example.evidence", "GenericSecretHolder"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenGenericFieldValue"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenBareFieldValue"),
                new ResolvedTypeIdentity("com.example.evidence", "ForbiddenAnnotationMember"));
    }

    @Test
    void should_extract_type_variable_binding_bounds_for_method_field_and_self_reference() {
        MethodSignature load = methodOf(evidenceClasses,
                "com.example.evidence.GenericBoundFixture", "load");
        TypeReference parameter = load.parameterTypeReferences().getFirst();
        TypeReference returned = load.returnType().orElseThrow();
        FieldInfo field = classOf(evidenceClasses, "com.example.evidence.BoundedBox").fields().stream()
                .filter(value -> "value".equals(value.name()))
                .findFirst()
                .orElseThrow();
        MethodSignature echo = methodOf(evidenceClasses,
                "com.example.evidence.GenericBoundFixture", "echo");

        assertThat(parameter.upperBounds()).extracting(TypeReference::resolvedType)
                .containsExactly("com.example.evidence.ForbiddenDto", "com.example.evidence.ForbiddenMarker");
        assertThat(returned.upperBounds()).extracting(TypeReference::resolvedType)
                .containsExactly("com.example.evidence.ForbiddenDto", "com.example.evidence.ForbiddenMarker");
        assertThat(field.typeReference().upperBounds()).singleElement()
                .extracting(TypeReference::resolvedType)
                .isEqualTo("com.example.evidence.ForbiddenDto");
        assertThat(echo.parameterTypeReferences()).singleElement().satisfies(selfReference -> {
            assertThat(selfReference.upperBounds()).singleElement().satisfies(comparable -> {
                assertThat(comparable.resolvedType()).isEqualTo("java.lang.Comparable");
                assertThat(comparable.typeArguments()).singleElement().satisfies(nested ->
                        assertThat(nested.upperBounds()).hasSize(0));
            });
        });
    }

    // --- MyBatis SQL ---

    @Test
    void should_prefer_annotation_sql_over_xml_when_both_declare_the_same_statement() {
        MethodSignature method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "findByOrderNo");

        assertThat(method.sqlSource()).isEqualTo(SqlSource.ANNOTATION);
        assertThat(method.sql())
                .as("fixture 的 XML 已改成 xml_marker，優先序反轉時這個斷言會變紅")
                .isEqualTo("SELECT * FROM orders WHERE order_no = #{orderNo}")
                .doesNotContain("xml_marker");
    }

    @Test
    void should_fall_back_to_xml_when_a_mapper_method_has_no_sql_annotation() {
        MethodSignature method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "xmlOnly");

        assertThat(method.sqlSource()).isEqualTo(SqlSource.MAPPER_XML);
        assertThat(method.sql()).isEqualTo("SELECT * FROM orders WHERE customer_id = #{customerId}");
    }

    @Test
    void should_read_the_value_attribute_form_when_a_select_is_written_with_a_named_attribute() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "findByCode").sql())
                .as("舊分析器只讀 single-member 形式，@Select(value=...) 會得到 null")
                .isEqualTo("SELECT * FROM accounts WHERE code = #{code}");
    }

    @Test
    void should_join_the_elements_when_a_select_is_written_as_a_string_array() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "findByStatus").sql())
                .as("舊分析器會得到字面的大括號字串")
                .isEqualTo("SELECT * FROM accounts WHERE status = #{status}");
    }

    @Test
    void should_include_the_enclosing_type_when_a_nested_mapper_is_qualified() {
        assertThat(classes)
                .as("舊分析器產出 com.example.syntax.Nested，永遠對不上 XML namespace")
                .anyMatch(metadata -> "com.example.syntax.AccountMapper.Nested"
                        .equals(metadata.fullyQualifiedName()));
    }

    @Test
    void should_leave_sql_source_unset_when_a_method_has_no_sql_at_all() {
        MethodSignature method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.sql()).isNull();
        assertThat(method.sqlSource()).isNull();
    }

    // --- 型別形狀 ---

    @Test
    void should_classify_records_and_enums_when_a_file_declares_several_type_kinds() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").kind()).isEqualTo(TypeKind.RECORD);
        assertThat(classOf(classes, "com.example.syntax.AccountStatus").kind()).isEqualTo(TypeKind.ENUM);
        assertThat(classOf(classes, "com.example.syntax.AccountMapper").kind()).isEqualTo(TypeKind.INTERFACE);
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").kind()).isEqualTo(TypeKind.CLASS);
    }

    @Test
    void should_expose_record_components_as_fields_when_a_record_is_scanned() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").fields())
                .extracting(ClassMetadata.FieldInfo::name, ClassMetadata.FieldInfo::type)
                .containsExactly(
                        tuple("accountNo", "String"),
                        tuple("total", "long"));
    }

    @Test
    void should_detect_fluent_accessors_when_the_class_declares_accessors_fluent_true() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").hasFluentAccessors())
                .as("舊分析器比對 pretty print 後的字串是否等於 true")
                .isTrue();
    }

    @Test
    void should_apply_explicit_and_default_accessors_chain_values() {
        assertThat(classOf(classes, "com.example.syntax.ExplicitChainedShapes").hasChainedAccessors())
                .isTrue();
        assertThat(classOf(classes, "com.example.syntax.ExplicitNonChainedShapes").hasChainedAccessors())
                .isFalse();
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").hasChainedAccessors())
                .as("Lombok defaults chain to fluent when chain is absent")
                .isTrue();
    }

    @Test
    void should_read_every_profile_when_the_profile_annotation_declares_an_array() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").profiles())
                .containsExactly("dev", "uat");
    }

    @Test
    void should_keep_the_short_annotation_name_when_the_source_writes_it_short() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").annotations())
                .contains("Service", "Profile", "Accessors");
    }

    @Test
    void should_keep_the_long_annotation_name_when_the_source_writes_it_fully_qualified() {
        assertThat(classOf(classes, "com.example.syntax.FullyQualifiedEndpoint").annotations())
                .as("metadata 保留寫法原貌；改用 simpleNameOf 縮短時這個斷言才會變紅")
                .contains("org.springframework.stereotype.Service",
                        "org.springframework.web.bind.annotation.RequestMapping")
                .doesNotContain("Service", "RequestMapping");
    }

    @Test
    void should_keep_the_long_method_annotation_name_when_a_mapping_is_written_fully_qualified() {
        assertThat(methodOf(classes, "com.example.syntax.FullyQualifiedEndpoint", "ping").annotations())
                .containsExactly("org.springframework.web.bind.annotation.GetMapping");
    }

    @Test
    void should_record_one_based_line_numbers_when_a_method_is_scanned() {
        MethodSignature method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.startLine()).isPositive();
        assertThat(method.endLine()).isGreaterThanOrEqualTo(method.startLine());
    }

    @Test
    void should_simplify_parameter_types_when_a_method_declares_qualified_or_generic_parameters() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "updateStatus").paramTypes())
                .containsExactly("Long", "String");
    }

    @Test
    void should_include_every_module_when_the_repository_is_a_maven_aggregator() {
        assertThat(multiModuleClasses)
                .extracting(ClassMetadata::fullyQualifiedName)
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
        assertThat(methodOf(syntax.classes(), "com.example.basic.BasicRepository", "findById").sql())
                .isEqualTo("SELECT name FROM basic_orders WHERE id = #{id}");
    }

    private ClassMetadata classOf(List<ClassMetadata> source, String fullyQualifiedName) {
        return source.stream()
                .filter(metadata -> fullyQualifiedName.equals(metadata.fullyQualifiedName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class metadata " + fullyQualifiedName));
    }

    private MethodSignature methodOf(List<ClassMetadata> source, String fullyQualifiedName, String methodName) {
        return classOf(source, fullyQualifiedName).methods().stream()
                .filter(method -> methodName.equals(method.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + fullyQualifiedName + "#" + methodName));
    }

    private SyntaxInvocation invocationOf(MethodSignature method, String expression) {
        return method.invocations().stream()
                .filter(invocation -> expression.equals(invocation.expression()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no invocation " + expression));
    }

    private SyntaxRange syntaxRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
