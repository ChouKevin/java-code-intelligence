package com.java.semantic.syntax.application.concept;

import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.CompositeTypeReference;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;

/** 僅從 binding 已證實的 metadata 投影 TYPE_USAGE 概念 */
public final class TypeUsageConceptProvider implements ConceptProvider {

    private static final String PROVIDER_ID = "type-usage";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<ConceptKind> supportedKinds() {
        return Set.copyOf(EnumSet.of(ConceptKind.TYPE_USAGE));
    }

    @Override
    public ConceptProviderProjection project(RepositorySyntax syntax) {
        List<ConceptCatalogEntry> entries = syntax.sourceTypes().stream()
                .flatMap(metadata -> entriesFor(metadata).stream())
                .toList();
        return new ConceptProviderProjection(entries, List.of());
    }

    private static List<ConceptCatalogEntry> entriesFor(SourceTypeMetadata metadata) {
        SourceTypeIdentity sourceType = metadata.declaration().identity();
        TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(sourceType);
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < metadata.relationships().implementedTypes().size(); index++) {
            entries.addAll(typeReferenceEntries(metadata, typeSubject,
                    new TypeUsageLocation(TypeUsageSlot.IMPLEMENTED_TYPE, index),
                    metadata.relationships().implementedTypes().get(index)));
        }
        for (int index = 0; index < metadata.relationships().extendedTypes().size(); index++) {
            entries.addAll(typeReferenceEntries(metadata, typeSubject,
                    new TypeUsageLocation(TypeUsageSlot.EXTENDED_TYPE, index),
                    metadata.relationships().extendedTypes().get(index)));
        }
        for (SourceFieldMetadata field : metadata.members().fields()) {
            FieldDeclarationSubjectIdentity subject = new FieldDeclarationSubjectIdentity(
                    new TypeMember(sourceType, field.name()));
            entries.addAll(typeReferenceEntries(metadata, subject,
                    new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0), field.typeReference()));
        }
        for (SourceMethodMetadata method : metadata.members().methods()) {
            DeclarationSubjectIdentity subject = DeclarationConceptProvider.methodSubject(metadata, method);
            for (int index = 0; index < method.parameterTypeReferences().size(); index++) {
                entries.addAll(typeReferenceEntries(metadata, subject,
                        new TypeUsageLocation(TypeUsageSlot.METHOD_PARAMETER, index),
                        method.parameterTypeReferences().get(index)));
            }
            method.returnType().ifPresent(returnType -> entries.addAll(typeReferenceEntries(metadata, subject,
                    new TypeUsageLocation(TypeUsageSlot.METHOD_RETURN, 0), returnType)));
            for (int index = 0; index < method.bodyTypeReferences().size(); index++) {
                entries.addAll(resolvedTypeEntries(metadata, subject,
                        new TypeUsageLocation(TypeUsageSlot.METHOD_BODY_OR_ANNOTATION_MEMBER, index),
                        TypeUsagePath.empty(), method.bodyTypeReferences().get(index), 0));
            }
        }
        return List.copyOf(entries);
    }

    private static List<ConceptCatalogEntry> typeReferenceEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            TypeReference reference) {
        return typeReferenceEntries(metadata, subject, location, TypeUsagePath.empty(), reference, 0);
    }

    private static List<ConceptCatalogEntry> typeReferenceEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            TypeReference reference,
            int arrayDimensions) {
        return switch (reference) {
            case NamedTypeReference named -> named.resolvedNamedType()
                    .map(resolvedType -> resolvedTypeEntries(
                            metadata, subject, location, path, resolvedType, arrayDimensions))
                    .orElseGet(List::of);
            case ParameterizedTypeReference parameterized -> parameterizedEntries(
                    metadata, subject, location, path, parameterized, arrayDimensions);
            case ArrayTypeReference array -> typeReferenceEntries(
                    metadata, subject, location, path, array.elementType(), arrayDimensions + array.dimensions());
            case WildcardTypeReference wildcard -> wildcardEntries(
                    metadata, subject, location, path, wildcard, arrayDimensions);
            case TypeVariableReference variable -> typeVariableEntries(
                    metadata, subject, location, path, variable, arrayDimensions);
            case CompositeTypeReference composite -> compositeEntries(
                    metadata, subject, location, path, composite, arrayDimensions);
            default -> List.of();
        };
    }

    private static List<ConceptCatalogEntry> parameterizedEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            ParameterizedTypeReference parameterized,
            int arrayDimensions) {
        List<ConceptCatalogEntry> entries = new ArrayList<>(typeReferenceEntries(
                metadata, subject, location, path, parameterized.rawType(), arrayDimensions));
        for (int index = 0; index < parameterized.typeArguments().size(); index++) {
            entries.addAll(typeReferenceEntries(
                    metadata,
                    subject,
                    location,
                    appendPath(path, new TypeUsagePath.TypeArgument(index)),
                    parameterized.typeArguments().get(index),
                    0));
        }
        return List.copyOf(entries);
    }

    private static List<ConceptCatalogEntry> wildcardEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            WildcardTypeReference wildcard,
            int arrayDimensions) {
        if (wildcard.upperBound().isPresent()) {
            return typeReferenceEntries(metadata, subject, location,
                    appendPath(path, new TypeUsagePath.WildcardExtendsBound()),
                    wildcard.upperBound().orElseThrow(), arrayDimensions);
        }
        if (wildcard.lowerBound().isPresent()) {
            return typeReferenceEntries(metadata, subject, location,
                    appendPath(path, new TypeUsagePath.WildcardSuperBound()),
                    wildcard.lowerBound().orElseThrow(), arrayDimensions);
        }
        return List.of();
    }

    private static List<ConceptCatalogEntry> typeVariableEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            TypeVariableReference variable,
            int arrayDimensions) {
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < variable.upperBounds().size(); index++) {
            entries.addAll(typeReferenceEntries(
                    metadata,
                    subject,
                    location,
                    appendPath(path, new TypeUsagePath.TypeVariableBound(index)),
                    variable.upperBounds().get(index),
                    arrayDimensions));
        }
        return List.copyOf(entries);
    }

    private static List<ConceptCatalogEntry> compositeEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            CompositeTypeReference composite,
            int arrayDimensions) {
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        for (TypeReference alternative : composite.alternatives()) {
            entries.addAll(typeReferenceEntries(metadata, subject, location, path, alternative, arrayDimensions));
        }
        return List.copyOf(entries);
    }

    private static List<TypeUsagePath> appendPath(List<TypeUsagePath> path, TypeUsagePath element) {
        List<TypeUsagePath> extendedPath = new ArrayList<>(path);
        extendedPath.add(element);
        return List.copyOf(extendedPath);
    }

    private static List<ConceptCatalogEntry> resolvedTypeEntries(
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            JavaTypeIdentity resolvedType,
            int arrayDimensions) {
        TypeUsageConceptIdentity identity = new TypeUsageConceptIdentity(
                subject,
                location,
                path,
                new ReferencedTypeIdentity(resolvedType, arrayDimensions));
        return List.of(new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                identity.referencedType().fullyQualifiedName() + " used by " + subject.displayValue(),
                metadata.declaration().identity().javaType().packageName(),
                Optional.of(metadata.declaration().identity().fullyQualifiedName()),
                ConceptAuthority.SYNTAX_RESOLVED,
                Set.of(identity)));
    }
}
