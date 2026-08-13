package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.CompilationUnitContext;
import com.java.semantic.syntax.domain.FrameworkTypeFacts;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceTypeDeclaration;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMembers;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.domain.SourceTypeRelationships;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TypeUsageConceptProvider 的宣告型別樹投影規則 */
class TypeUsageConceptProviderTest {

    private final TypeUsageConceptProvider provider = new TypeUsageConceptProvider();

    @Test
    void should_project_declaration_type_trees_with_structural_paths_and_flat_body_usages() {
        List<TypeUsageConceptIdentity> identities = provider.project(repositorySyntax()).entries().stream()
                .map(ConceptCatalogEntry::identity)
                .filter(TypeUsageConceptIdentity.class::isInstance)
                .map(TypeUsageConceptIdentity.class::cast)
                .toList();

        TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(sourceType());
        FieldDeclarationSubjectIdentity nestedField = fieldSubject("nested", "List<Map<Order[], ? extends Auditable>>");
        FieldDeclarationSubjectIdentity repeatedField = fieldSubject("repeated", "Map<Order, Order>");
        FieldDeclarationSubjectIdentity boundedField = fieldSubject("bounded", "T");
        FieldDeclarationSubjectIdentity recursiveField = fieldSubject("recursive", "S");
        ResolvedMethodDeclarationSubjectIdentity methodSubject = new ResolvedMethodDeclarationSubjectIdentity(target());

        assertThat(identities).containsExactlyInAnyOrder(
                usage(typeSubject, TypeUsageSlot.EXTENDED_TYPE, 0, List.of(), "BaseHandler", 0),
                usage(typeSubject, TypeUsageSlot.EXTENDED_TYPE, 0, List.of(new TypeUsagePath.TypeArgument(0)), "Order", 0),
                usage(typeSubject, TypeUsageSlot.IMPLEMENTED_TYPE, 0, List.of(), "OrderPort", 0),
                usage(typeSubject, TypeUsageSlot.IMPLEMENTED_TYPE, 0,
                        List.of(new TypeUsagePath.TypeArgument(0), new TypeUsagePath.WildcardSuperBound()),
                        "Auditable", 0),
                usage(nestedField, TypeUsageSlot.FIELD_DECLARATION, 0, List.of(), "List", 0),
                usage(nestedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeArgument(0)), "Map", 0),
                usage(nestedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeArgument(0), new TypeUsagePath.TypeArgument(0)), "Order", 1),
                usage(nestedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeArgument(0), new TypeUsagePath.TypeArgument(1),
                                new TypeUsagePath.WildcardExtendsBound()),
                        "Auditable", 0),
                usage(repeatedField, TypeUsageSlot.FIELD_DECLARATION, 0, List.of(), "Map", 0),
                usage(repeatedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeArgument(0)), "Order", 0),
                usage(repeatedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeArgument(1)), "Order", 0),
                usage(boundedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(0)), "Order", 0),
                usage(boundedField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(1)), "Auditable", 0),
                usage(methodSubject, TypeUsageSlot.METHOD_PARAMETER, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(0)), "Order", 0),
                usage(methodSubject, TypeUsageSlot.METHOD_PARAMETER, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(1)), "Auditable", 0),
                usage(methodSubject, TypeUsageSlot.METHOD_RETURN, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(0)), "Order", 0),
                usage(methodSubject, TypeUsageSlot.METHOD_RETURN, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(1)), "Auditable", 0),
                usage(recursiveField, TypeUsageSlot.FIELD_DECLARATION, 0,
                        List.of(new TypeUsagePath.TypeVariableBound(0)), "Comparable", 0),
                usage(methodSubject, TypeUsageSlot.METHOD_BODY_OR_ANNOTATION_MEMBER, 0, List.of(), "BodyOnly", 0));
    }

    private static RepositorySyntax repositorySyntax() {
        TypeVariableReference bounds = new TypeVariableReference(
                "T", "T", List.of(named("Order"), named("Auditable")), true);
        TypeVariableReference recursive = new TypeVariableReference(
                "S", "S", List.of(parameterized("Comparable<S>", "Comparable", List.of(
                        new TypeVariableReference("S", "S", List.of(), true)))), true);
        SourceMethodMetadata method = method(bounds);
        SourceTypeMetadata type = new SourceTypeMetadata(
                new SourceTypeDeclaration(
                        new SourceTypeIdentity(new JavaTypeIdentity(PACKAGE_NAME, "UsageFixture"), SOURCE_FILE),
                        SourceTypeKind.CLASS,
                        false,
                        source()),
                new SourceTypeRelationships(
                        List.of(parameterized("BaseHandler<Order>", "BaseHandler", List.of(named("Order")))),
                        List.of(parameterized("OrderPort<? super Auditable>", "OrderPort", List.of(
                                new WildcardTypeReference("? super Auditable", Optional.empty(),
                                        Optional.of(named("Auditable")), true))))),
                new SourceTypeMembers(List.of(
                        field("nested", "List<Map<Order[], ? extends Auditable>>", parameterized(
                                "List<Map<Order[], ? extends Auditable>>", "List", List.of(parameterized(
                                        "Map<Order[], ? extends Auditable>", "Map", List.of(
                                                new ArrayTypeReference("Order[]", named("Order"), 1),
                                                new WildcardTypeReference("? extends Auditable", Optional.of(named("Auditable")),
                                                        Optional.empty(), true)))))),
                        field("repeated", "Map<Order, Order>", parameterized("Map<Order, Order>", "Map",
                                List.of(named("Order"), named("Order")))),
                        field("bounded", "T", bounds),
                        field("recursive", "S", recursive)),
                        List.of(method), List.of(), List.of(), false, false),
                new FrameworkTypeFacts(List.of(), List.of(), false, List.of()),
                new CompilationUnitContext(List.of()));
        return new RepositorySyntax(List.of(), List.of(type));
    }

    private static SourceMethodMetadata method(TypeVariableReference bounds) {
        return new SourceMethodMetadata(
                "process",
                List.of("T"),
                null,
                Optional.empty(),
                source(),
                List.of(bounds),
                Optional.of(bounds),
                List.of(),
                List.of(),
                List.of(type("BodyOnly")),
                new SyntaxPosition(0, 0),
                MethodTargetResolution.resolved(target()),
                true,
                false,
                false);
    }

    private static SourceFieldMetadata field(String name, String declaredType, TypeReference reference) {
        return new SourceFieldMetadata(name, declaredType, "", reference, List.of());
    }

    private static FieldDeclarationSubjectIdentity fieldSubject(String name, String ignoredDeclaredType) {
        return new FieldDeclarationSubjectIdentity(new TypeMember(sourceType(), name));
    }

    private static TypeUsageConceptIdentity usage(
            UsageConceptIdentity.DeclarationSubjectIdentity subject,
            TypeUsageSlot slot,
            int index,
            List<TypeUsagePath> path,
            String typeName,
            int arrayDimensions) {
        return new TypeUsageConceptIdentity(
                subject,
                new TypeUsageLocation(slot, index),
                path,
                new ReferencedTypeIdentity(type(typeName), arrayDimensions));
    }

    private static ParameterizedTypeReference parameterized(
            String writtenType,
            String rawType,
            List<TypeReference> typeArguments) {
        return new ParameterizedTypeReference(writtenType, named(rawType), typeArguments);
    }

    private static NamedTypeReference named(String className) {
        return new NamedTypeReference(className, className, Optional.of(type(className)), true);
    }

    private static JavaTypeIdentity type(String className) {
        return new JavaTypeIdentity(PACKAGE_NAME, className);
    }

    private static MethodTarget target() {
        return new MethodTarget(
                sourceType(),
                "process",
                List.of("T"));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity(PACKAGE_NAME, "UsageFixture"), SOURCE_FILE);
    }

    private static SyntaxRange range() {
        return new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1));
    }

    private static SourceRange source() {
        return new SourceRange(SOURCE_FILE, range());
    }

    private static final String PACKAGE_NAME = "com.acme.usage";

    private static final String SOURCE_FILE = "src/main/java/com/acme/usage/UsageFixture.java";

    private static final String OWNER_TYPE = "com.acme.usage.UsageFixture";
}
