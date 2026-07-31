package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.ConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageSlot;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.TypeReference;

import org.springframework.util.StringUtils;

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
        for (ClassMetadata metadata : syntax.classes()) {
            TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(
                    metadata.sourceFile(), metadata.fullyQualifiedName());
            for (int index = 0; index < metadata.implementedTypeReferences().size(); index++) {
                addTypeReference(entries, metadata, typeSubject,
                        new TypeUsageLocation(TypeUsageSlot.IMPLEMENTED_TYPE, index),
                        metadata.implementedTypeReferences().get(index));
            }
            for (int index = 0; index < metadata.extendedTypeReferences().size(); index++) {
                addTypeReference(entries, metadata, typeSubject,
                        new TypeUsageLocation(TypeUsageSlot.EXTENDED_TYPE, index),
                        metadata.extendedTypeReferences().get(index));
            }
            for (FieldInfo field : metadata.fields()) {
                FieldDeclarationSubjectIdentity subject = new FieldDeclarationSubjectIdentity(
                        metadata.sourceFile(), metadata.fullyQualifiedName(), field.name(), field.type());
                addTypeReference(entries, metadata, subject, new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0),
                        field.typeReference());
            }
            for (MethodSignature method : metadata.methods()) {
                DeclarationSubjectIdentity subject = DeclarationConceptProvider.methodSubject(metadata, method);
                for (int index = 0; index < method.parameterTypeReferences().size(); index++) {
                    addTypeReference(entries, metadata, subject,
                            new TypeUsageLocation(TypeUsageSlot.METHOD_PARAMETER, index),
                            method.parameterTypeReferences().get(index));
                }
                method.returnType().ifPresent(returnType -> addTypeReference(entries, metadata, subject,
                        new TypeUsageLocation(TypeUsageSlot.METHOD_RETURN, 0), returnType));
                for (int index = 0; index < method.bodyTypeReferences().size(); index++) {
                    ResolvedTypeIdentity reference = method.bodyTypeReferences().get(index);
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
            ClassMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            TypeReference reference) {
        if (StringUtils.hasText(reference.resolvedType())) {
            addResolvedType(entries, metadata, subject, location, reference.resolvedType());
        }
    }

    private static void addResolvedType(
            List<ConceptCatalogEntry> entries,
            ClassMetadata metadata,
            DeclarationSubjectIdentity subject,
            TypeUsageLocation location,
            String resolvedType) {
        TypeUsageConceptIdentity identity = new TypeUsageConceptIdentity(
                metadata.sourceFile(), subject, location, resolvedType);
        entries.add(new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                resolvedType + "@" + subject.displayValue() + "[" + location.slot() + ":" + location.index() + "]",
                resolvedType + " used by " + subject.displayValue(),
                ConceptSearchTokenizer.tokenize(resolvedType + " " + subject.displayValue()),
                metadata.packageName(),
                Optional.of(metadata.fullyQualifiedName()),
                ConceptAuthority.SYNTAX_RESOLVED,
                Set.of(identity)));
    }
}
