package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.ConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageSlot;
import com.java.semantic.identity.JavaTypeIdentity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.TypeReference;

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
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(
                    metadata.declaration().identity().sourceFile(), metadata.declaration().identity().fullyQualifiedName());
            for (int index = 0; index < metadata.relationships().implementedTypes().size(); index++) {
                addTypeReference(entries, metadata, typeSubject,
                        new TypeUsageLocation(TypeUsageSlot.IMPLEMENTED_TYPE, index),
                        metadata.relationships().implementedTypes().get(index));
            }
            for (int index = 0; index < metadata.relationships().extendedTypes().size(); index++) {
                addTypeReference(entries, metadata, typeSubject,
                        new TypeUsageLocation(TypeUsageSlot.EXTENDED_TYPE, index),
                        metadata.relationships().extendedTypes().get(index));
            }
            for (SourceFieldMetadata field : metadata.members().fields()) {
                FieldDeclarationSubjectIdentity subject = new FieldDeclarationSubjectIdentity(
                        metadata.declaration().identity().sourceFile(), metadata.declaration().identity().fullyQualifiedName(),
                        field.name(), field.type());
                addTypeReference(entries, metadata, subject, new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0),
                        field.typeReference());
            }
            for (SourceMethodMetadata method : metadata.members().methods()) {
                DeclarationSubjectIdentity subject = DeclarationConceptProvider.methodSubject(metadata, method);
                for (int index = 0; index < method.parameterTypeReferences().size(); index++) {
                    addTypeReference(entries, metadata, subject,
                            new TypeUsageLocation(TypeUsageSlot.METHOD_PARAMETER, index),
                            method.parameterTypeReferences().get(index));
                }
                method.returnType().ifPresent(returnType -> addTypeReference(entries, metadata, subject,
                        new TypeUsageLocation(TypeUsageSlot.METHOD_RETURN, 0), returnType));
                for (int index = 0; index < method.bodyTypeReferences().size(); index++) {
                    JavaTypeIdentity reference = method.bodyTypeReferences().get(index);
                    addResolvedType(entries, metadata, subject,
                            new TypeUsageLocation(TypeUsageSlot.METHOD_BODY_OR_ANNOTATION_MEMBER, index),
                            DeclarationConceptProvider.resolvedTypeName(reference));
                }
            }
        }
        return new ConceptProviderProjection(entries, List.of());
    }

    private static void addTypeReference(
            List<ConceptCatalogEntry> entries,
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            TypeReference reference) {
        reference.resolvedTypeName().ifPresent(resolvedType ->
                addResolvedType(entries, metadata, subject, location, resolvedType));
    }

    private static void addResolvedType(
            List<ConceptCatalogEntry> entries,
            SourceTypeMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            String resolvedType) {
        TypeUsageConceptIdentity identity = new TypeUsageConceptIdentity(
                metadata.declaration().identity().sourceFile(), subject, location, resolvedType);
        entries.add(new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                resolvedType + "@" + subject.displayValue() + "[" + location.slot() + ":" + location.index() + "]",
                resolvedType + " used by " + subject.displayValue(),
                ConceptSearchTokenizer.tokenize(resolvedType + " " + subject.displayValue()),
                metadata.declaration().identity().javaType().packageName(),
                Optional.of(metadata.declaration().identity().fullyQualifiedName()),
                ConceptAuthority.SYNTAX_RESOLVED,
                Set.of(identity)));
    }
}
