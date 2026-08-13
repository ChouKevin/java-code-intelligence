package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.concept.ConceptPage;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.domain.SourceTypeMetadataFixture;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.DiscoveryFollowUp.AnalyzeCallGraphRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.DiscoverMethodImplementationsRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.FindInternalReferencesRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMethodSourceRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetTypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.Operation;
import com.java.semantic.syntax.application.DiscoveryFollowUp.TypeMembersRequest;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceEnumConstantMetadata;
import com.java.semantic.syntax.domain.SourceRecordComponentMetadata;
import com.java.semantic.syntax.domain.SourceTypeMembers;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.NamedTypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 固定版本型別成員合併分頁與可執行 follow-up 的應用服務契約 */
@ExtendWith(MockitoExtension.class)
class TypeMemberDiscoveryApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final Path REPOSITORY_ROOT = Path.of("/workspace/orders");
    private static final String SOURCE_FILE = "module-a/src/main/java/com/acme/order/OrderService.java";
    private static final String DUPLICATE_SOURCE_FILE =
            "module-b/src/main/java/com/acme/order/OrderService.java";
    private static final String TYPE_NAME = "com.acme.order.OrderService";
    private static final SourceTypeIdentity SOURCE_TYPE = new SourceTypeIdentity(
            new JavaTypeIdentity("com.acme.order", "OrderService"), SOURCE_FILE);
    private static final String REFERENCED_SOURCE_FILE =
            "module-a/src/main/java/com/acme/order/Order.java";
    private static final String DUPLICATE_REFERENCED_SOURCE_FILE =
            "module-b/src/main/java/com/acme/order/Order.java";
    private static final String REFERENCED_TYPE_NAME = "com.acme.order.Order";

    @Mock
    private RepositoryApplicationService repositoryApplicationService;

    @Mock
    private RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;

    private TypeMemberDiscoveryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new TypeMemberDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                new DiscoveryFollowUpFactory());
    }

    @Test
    void should_page_one_deterministic_combined_sequence_and_return_complete_follow_up_requests() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        RepositorySyntax syntax = syntax();
        TypeMemberQuery firstQuery = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(TypeMemberKind.FIELD, TypeMemberKind.METHOD),
                Optional.empty(),
                101,
                5);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(syntax);

        TypeMemberResult first = service.discover(firstQuery);

        assertThat(first.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(first.analyzedRevision()).isEqualTo(REVISION);
        assertThat(first.sourceType()).isEqualTo(SOURCE_TYPE);
        assertThat(first.typeKind()).isEqualTo(SourceTypeKind.CLASS);
        assertThat(first.annotations()).containsExactly("Service");
        assertThat(first.implementedTypes()).containsExactly("OrderPort");
        assertThat(first.extendedTypes()).containsExactly("BaseOrderService");
        assertThat(first.page()).isEqualTo(new ConceptPage(101, 5, 5, 206, true));
        assertThat(first.members()).extracting(TypeMember::kind)
                .containsExactly(
                        TypeMemberKind.METHOD,
                        TypeMemberKind.METHOD,
                        TypeMemberKind.FIELD,
                        TypeMemberKind.FIELD,
                        TypeMemberKind.FIELD);

        List<MethodTypeMember> methods = first.members().stream()
                .filter(MethodTypeMember.class::isInstance)
                .map(MethodTypeMember.class::cast)
                .toList();
        assertThat(methods).extracting(member -> member.target().parameterTypes().getFirst())
                .containsExactly("com.acme.Alpha", "com.acme.Beta");
        MethodTypeMember method = methods.getFirst();
        assertThat(method.availableFollowUps()).extracting(DiscoveryFollowUp::operation)
                .containsExactly(
                        Operation.GET_METHOD_SOURCE,
                        Operation.ANALYZE_OUTGOING_CALL_GRAPH,
                        Operation.ANALYZE_INCOMING_CALL_GRAPH,
                        Operation.GET_TYPE_MEMBERS);
        assertThat(method.availableFollowUps()).extracting(DiscoveryFollowUp::api)
                .containsExactly(
                        new DiscoveryFollowUp.ApiProjection(
                                "POST", "/v1/discovery/method-source", "getMethodSource"),
                        new DiscoveryFollowUp.ApiProjection(
                                "POST", "/v1/analyses/call-graphs/outgoing", "analyzeOutgoingCallGraph"),
                        new DiscoveryFollowUp.ApiProjection(
                                "POST", "/v1/analyses/call-graphs/incoming", "analyzeIncomingCallGraph"),
                        new DiscoveryFollowUp.ApiProjection(
                                "POST", "/v1/discovery/type-members", "discoverTypeMembers"));
        assertThat(method.availableFollowUps().getFirst().request())
                .isEqualTo(new GetMethodSourceRequest(
                        REPOSITORY_ID.value(), REVISION.value(), method.target()));
        assertThat(method.availableFollowUps().get(1).request())
                .isEqualTo(new AnalyzeCallGraphRequest(
                        REPOSITORY_ID.value(), REVISION.value(), 2, method.target()));
        assertThat(method.availableFollowUps().get(2).request())
                .isEqualTo(new AnalyzeCallGraphRequest(
                        REPOSITORY_ID.value(), REVISION.value(), 2, method.target()));
        assertThat(method.availableFollowUps().get(3).request())
                .isEqualTo(new GetTypeMembersRequest(
                        REPOSITORY_ID.value(),
                        REVISION.value(),
                        method.target().sourceType(),
                        List.of(TypeMemberKind.FIELD),
                        Optional.empty(),
                        0,
                        50));

        List<FieldTypeMember> fields = first.members().stream()
                .filter(FieldTypeMember.class::isInstance)
                .map(FieldTypeMember.class::cast)
                .toList();
        assertThat(fields).extracting(FieldTypeMember::fieldName, FieldTypeMember::writtenType)
                .containsExactly(
                        tuple("aShared", "AlphaType"),
                        tuple("aShared", "ZuluType"),
                        tuple("field000", "Type000"));
        assertThat(fields.getFirst().resolvedType()).contains("com.acme.type.AlphaType");
        assertThat(fields.getFirst().annotations()).containsExactly("Autowired");
        assertThat(fields.getFirst().limitations()).containsExactly(TypeMemberLimitation.FIELD_USAGE_NOT_INDEXED);
        assertThat(fields.getFirst().availableFollowUps()).singleElement()
                .satisfies(followUp -> assertSelfReference(followUp, "aShared"));
        assertThat(fields.get(1).resolvedType()).isEmpty();
        assertThat(fields.get(1).availableFollowUps()).singleElement()
                .satisfies(followUp -> assertSelfReference(followUp, "aShared"));

        assertThat(first.availableFollowUps()).singleElement()
                .satisfies(followUp -> {
                    assertThat(followUp.operation()).isEqualTo(Operation.DISCOVER_TYPE_MEMBERS);
                    assertThat(followUp.api()).isEqualTo(new DiscoveryFollowUp.ApiProjection(
                            "POST", "/v1/discovery/type-members", "discoverTypeMembers"));
                    assertThat(followUp.request()).isEqualTo(new TypeMembersRequest(
                            REPOSITORY_ID.value(),
                            REVISION.value(),
                            SOURCE_TYPE,
                            List.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                            Optional.empty(),
                            106,
                            5));
                });

        TypeMemberResult second = service.discover(firstQuery.nextPage(106));

        assertThat(second.sourceType()).isEqualTo(first.sourceType());
        assertThat(second.typeKind()).isEqualTo(first.typeKind());
        assertThat(second.annotations()).isEqualTo(first.annotations());
        assertThat(second.implementedTypes()).isEqualTo(first.implementedTypes());
        assertThat(second.extendedTypes()).isEqualTo(first.extendedTypes());
        assertThat(second.members()).extracting(TypeMember::kind)
                .containsOnly(TypeMemberKind.FIELD);
    }

    @Test
    void should_preserve_name_prefix_in_next_page_follow_up_request() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        TypeMemberQuery firstQuery = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(TypeMemberKind.FIELD, TypeMemberKind.METHOD),
                Optional.of("shared"),
                0,
                1);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(any())).thenReturn(syntax());

        TypeMemberResult first = service.discover(firstQuery);

        assertThat(first.page()).isEqualTo(new ConceptPage(0, 1, 1, 2, true));
        assertThat(first.availableFollowUps()).singleElement()
                .satisfies(followUp -> {
                    assertThat(followUp.operation()).isEqualTo(Operation.DISCOVER_TYPE_MEMBERS);
                    assertThat(followUp.request()).isEqualTo(new TypeMembersRequest(
                            REPOSITORY_ID.value(),
                            REVISION.value(),
                            SOURCE_TYPE,
                            List.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                            Optional.of("shared"),
                            1,
                            1));
                });
    }

    @Test
    void should_merge_enum_constants_and_record_components_in_fixed_order_with_exact_follow_ups() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        TypeMemberQuery query = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(
                        TypeMemberKind.METHOD,
                        TypeMemberKind.FIELD,
                        TypeMemberKind.ENUM_CONSTANT,
                        TypeMemberKind.RECORD_COMPONENT),
                Optional.of("value"),
                0,
                3);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(new RepositorySyntax(
                List.of(), List.of(valueMetadata()), List.of()));

        TypeMemberResult first = service.discover(query);

        assertThat(first.members()).extracting(TypeMember::kind).containsExactly(
                TypeMemberKind.METHOD,
                TypeMemberKind.FIELD,
                TypeMemberKind.ENUM_CONSTANT);
        assertThat(first.members().get(2)).isInstanceOfSatisfying(EnumConstantTypeMember.class, constant -> {
            assertThat(constant.constantName()).isEqualTo("valueConstant");
            assertThat(constant.availableFollowUps()).singleElement()
                    .satisfies(followUp -> assertSelfReference(followUp, "valueConstant"));
        });
        assertThat(first.availableFollowUps()).singleElement().satisfies(followUp ->
                assertThat(followUp.request()).isEqualTo(new TypeMembersRequest(
                        REPOSITORY_ID.value(), REVISION.value(), SOURCE_TYPE,
                        List.of(
                                TypeMemberKind.METHOD,
                                TypeMemberKind.FIELD,
                                TypeMemberKind.ENUM_CONSTANT,
                                TypeMemberKind.RECORD_COMPONENT),
                        Optional.of("value"), 3, 3)));

        TypeMemberResult second = service.discover(query.nextPage(3));

        assertThat(second.members()).singleElement().isInstanceOfSatisfying(RecordComponentTypeMember.class,
                component -> {
                    assertThat(component.componentName()).isEqualTo("valueComponent");
                    assertThat(component.writtenType()).isEqualTo("Order");
                    assertThat(component.availableFollowUps()).singleElement()
                            .satisfies(followUp -> assertSelfReference(followUp, "valueComponent"));
                });
    }

    @Test
    void should_offer_implementation_discovery_only_for_an_eligible_abstract_declaration() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        SourceMethodMetadata declaration = abstractMethod(SOURCE_FILE, "shared", List.of());
        SourceTypeMetadata metadata = SourceTypeMetadataFixture.sourceType(
                "OrderService",
                "com.acme.order",
                TYPE_NAME,
                SOURCE_FILE,
                SourceTypeKind.INTERFACE,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(declaration),
                false,
                false,
                List.of(),
                range(),
                source(SOURCE_FILE),
                false,
                List.of());
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(new RepositorySyntax(List.of(), List.of(metadata), List.of()));

        TypeMemberResult result = service.discover(new TypeMemberQuery(
                REPOSITORY_ID, REVISION, SOURCE_TYPE, Set.of(TypeMemberKind.METHOD), Optional.empty(), 0, 10));

        MethodTypeMember member = (MethodTypeMember) result.members().getFirst();
        assertThat(member.availableFollowUps()).extracting(DiscoveryFollowUp::operation)
                .containsExactly(
                        Operation.GET_METHOD_SOURCE,
                        Operation.ANALYZE_OUTGOING_CALL_GRAPH,
                        Operation.ANALYZE_INCOMING_CALL_GRAPH,
                        Operation.GET_TYPE_MEMBERS,
                        Operation.DISCOVER_METHOD_IMPLEMENTATIONS);
        assertThat(member.availableFollowUps().get(4).request())
                .isEqualTo(new DiscoverMethodImplementationsRequest(
                        REPOSITORY_ID.value(), REVISION.value(), member.target()));
    }

    @Test
    void should_reject_a_caller_filesystem_path_before_repository_access() {
        assertThatThrownBy(() -> new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderService"),
                        "/workspace/orders/src/main/java/com/acme/order/OrderService.java"),
                Set.of(TypeMemberKind.METHOD),
                Optional.empty(),
                0,
                50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository-relative");
    }

    @Test
    void should_preserve_array_dimensions_in_resolved_field_type_and_follow_up() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        TypeMemberQuery query = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                10);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(any())).thenReturn(new RepositorySyntax(
                List.of(),
                List.of(arrayMetadata()),
                List.of()));

        TypeMemberResult result = service.discover(query);

        assertThat(result.members()).singleElement().isInstanceOfSatisfying(
                FieldTypeMember.class,
                field -> {
                    assertThat(field.writtenType()).isEqualTo("Order[][]");
                    assertThat(field.resolvedType()).contains("com.acme.order.Order[][]");
                    assertThat(field.availableFollowUps()).singleElement()
                            .satisfies(followUp -> assertSelfReference(followUp, "orders"));
                });
    }

    @Test
    void should_offer_an_exact_concept_resolve_follow_up_for_a_uniquely_declared_field_type() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        TypeMemberQuery query = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                10);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(any())).thenReturn(new RepositorySyntax(
                List.of(),
                List.of(arrayMetadata(), referencedOrderMetadata(REFERENCED_SOURCE_FILE)),
                List.of()));

        TypeMemberResult result = service.discover(query);

        assertThat(result.members()).singleElement().isInstanceOfSatisfying(
                FieldTypeMember.class,
                field -> {
                    assertThat(field.resolvedType()).contains("com.acme.order.Order[][]");
                    assertThat(field.availableFollowUps()).satisfiesExactly(followUp -> {
                        assertThat(followUp.operation()).isEqualTo(Operation.RESOLVE_CONCEPT);
                        assertThat(followUp.request()).isEqualTo(new DiscoveryFollowUp.ResolveConceptRequest(
                                REPOSITORY_ID.value(),
                                REVISION.value(),
                                new TypeConceptIdentity(new SourceTypeIdentity(
                                        new JavaTypeIdentity("com.acme.order", "Order"),
                                        REFERENCED_SOURCE_FILE))));
                    }, followUp -> assertSelfReference(followUp, "orders"));
                });
    }

    @Test
    void should_not_offer_an_exact_concept_resolve_follow_up_for_an_ambiguously_declared_field_type() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        TypeMemberQuery query = new TypeMemberQuery(
                REPOSITORY_ID,
                REVISION,
                SOURCE_TYPE,
                Set.of(TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                10);
        delegateSnapshot(snapshot);
        when(repositorySyntaxProvider.get(any())).thenReturn(new RepositorySyntax(
                List.of(),
                List.of(
                        arrayMetadata(),
                        referencedOrderMetadata(REFERENCED_SOURCE_FILE),
                        referencedOrderMetadata(DUPLICATE_REFERENCED_SOURCE_FILE)),
                List.of()));

        TypeMemberResult result = service.discover(query);

        assertThat(result.members()).singleElement().isInstanceOfSatisfying(
                FieldTypeMember.class,
                field -> {
                    assertThat(field.writtenType()).isEqualTo("Order[][]");
                    assertThat(field.resolvedType()).contains("com.acme.order.Order[][]");
                    assertThat(field.availableFollowUps()).singleElement()
                            .satisfies(followUp -> assertSelfReference(followUp, "orders"));
                });
    }

    private static void assertSelfReference(DiscoveryFollowUp followUp, String fieldName) {
        assertThat(followUp.operation()).isEqualTo(Operation.FIND_INTERNAL_REFERENCES);
        assertThat(followUp.request()).isEqualTo(new FindInternalReferencesRequest(
                REPOSITORY_ID.value(),
                REVISION.value(),
                new ExactSourceDeclarationTarget.Member(new SourceMemberIdentity.TypeMember(SOURCE_TYPE, fieldName)),
                0,
                20));
    }

    private void delegateSnapshot(RepositorySnapshot snapshot) {
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, TypeMemberResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
    }

    private static RepositorySyntax syntax() {
        return new RepositorySyntax(
                List.of(),
                List.of(metadata(SOURCE_FILE), duplicateMetadata()),
                List.of());
    }

    private static SourceTypeMetadata metadata(String sourceFile) {
        List<SourceMethodMetadata> methods = new ArrayList<>(IntStream.range(0, 101)
                .mapToObj(index -> method(sourceFile, "method%03d".formatted(index), List.of()))
                .toList());
        methods.add(method(sourceFile, "shared", List.of("com.acme.Beta")));
        methods.add(method(sourceFile, "shared", List.of("com.acme.Alpha")));
        Collections.reverse(methods);

        List<SourceFieldMetadata> fields = new ArrayList<>(IntStream.range(0, 101)
                .mapToObj(index -> field(
                        "field%03d".formatted(index),
                        "Type%03d".formatted(index),
                        "com.acme.type.Type%03d".formatted(index)))
                .toList());
        fields.add(field("aShared", "ZuluType", ""));
        fields.add(field("aShared", "AlphaType", "com.acme.type.AlphaType"));
        Collections.reverse(fields);

        return SourceTypeMetadataFixture.sourceType(
                "OrderService",
                "com.acme.order",
                TYPE_NAME,
                sourceFile,
                SourceTypeKind.CLASS,
                false,
                List.of("OrderPort"),
                List.of("BaseOrderService"),
                List.of("Service"),
                List.of(),
                fields,
                methods,
                false,
                false,
                List.of(),
                range(),
                source(sourceFile),
                false,
                List.of());
    }

    private static SourceTypeMetadata valueMetadata() {
        SourceMethodMetadata method = method(SOURCE_FILE, "valueMethod", List.of());
        SourceFieldMetadata field = field("valueField", "Order", "com.acme.order.Order");
        SourceRange declaration = source(SOURCE_FILE);
        return new SourceTypeMetadata(
                new com.java.semantic.syntax.domain.SourceTypeDeclaration(SOURCE_TYPE, SourceTypeKind.CLASS, false, declaration),
                new com.java.semantic.syntax.domain.SourceTypeRelationships(List.of(), List.of()),
                new SourceTypeMembers(
                        List.of(field),
                        List.of(method),
                        List.of(new SourceEnumConstantMetadata("valueConstant", declaration, List.of())),
                        List.of(new SourceRecordComponentMetadata(
                                "valueComponent", "Order", "", namedTypeReference("Order", "com.acme.order.Order"),
                                declaration, List.of())),
                        false,
                        false),
                new com.java.semantic.syntax.domain.FrameworkTypeFacts(List.of(), List.of(), false, List.of()),
                new com.java.semantic.syntax.domain.CompilationUnitContext(List.of()));
    }

    private static SourceTypeMetadata duplicateMetadata() {
        return SourceTypeMetadataFixture.sourceType(
                "OrderService",
                "com.acme.order",
                TYPE_NAME,
                DUPLICATE_SOURCE_FILE,
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(method(DUPLICATE_SOURCE_FILE, "mustNotLeak", List.of())),
                false,
                false,
                List.of(),
                range(),
                source(DUPLICATE_SOURCE_FILE),
                false,
                List.of());
    }

    private static SourceTypeMetadata arrayMetadata() {
        SourceFieldMetadata field = new SourceFieldMetadata(
                "orders",
                "Order[][]",
                "",
                new ArrayTypeReference(
                        "Order[][]",
                        namedTypeReference("Order", "com.acme.order.Order"),
                        2),
                List.of());
        return SourceTypeMetadataFixture.sourceType(
                "OrderService",
                "com.acme.order",
                TYPE_NAME,
                SOURCE_FILE,
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(field),
                List.of(),
                false,
                false,
                List.of(),
                range(),
                source(SOURCE_FILE),
                false,
                List.of());
    }

    private static SourceTypeMetadata referencedOrderMetadata(String sourceFile) {
        return SourceTypeMetadataFixture.sourceType(
                "Order",
                "com.acme.order",
                REFERENCED_TYPE_NAME,
                sourceFile,
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                List.of(),
                range(),
                source(sourceFile),
                false,
                List.of());
    }

    private static SourceMethodMetadata method(String sourceFile, String name, List<String> parameterTypes) {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderService"),
                        sourceFile),
                name,
                parameterTypes);
        return new SourceMethodMetadata(
                name,
                parameterTypes,
                null,
                Optional.empty(),
                source(sourceFile),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                new SyntaxPosition(1, 1),
                MethodTargetResolution.resolved(target),
                true,
                false,
                false);
    }

    private static SourceMethodMetadata abstractMethod(String sourceFile, String name, List<String> parameterTypes) {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderService"),
                        sourceFile),
                name,
                parameterTypes);
        return new SourceMethodMetadata(
                name,
                parameterTypes,
                null,
                Optional.empty(),
                source(sourceFile),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                new SyntaxPosition(1, 1),
                MethodTargetResolution.resolved(target),
                false,
                true,
                true);
    }

    private static SourceFieldMetadata field(String name, String writtenType, String resolvedType) {
        return new SourceFieldMetadata(
                name,
                writtenType,
                "",
                namedTypeReference(writtenType, resolvedType),
                List.of(new AnnotationEvidence("Autowired", Optional.empty())));
    }

    private static NamedTypeReference namedTypeReference(String writtenType, String resolvedType) {
        int lastDot = resolvedType.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : resolvedType.substring(0, lastDot);
        String className = lastDot < 0 ? resolvedType : resolvedType.substring(lastDot + 1);
        if (lastDot < 0) {
            return new NamedTypeReference(writtenType, writtenType, Optional.empty(), false);
        }
        return new NamedTypeReference(writtenType, className,
                Optional.of(new JavaTypeIdentity(packageName, className)), true);
    }

    private static SyntaxRange range() {
        return new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(2, 0));
    }

    private static SourceRange source(String sourceFile) {
        return new SourceRange(sourceFile, range());
    }
}
