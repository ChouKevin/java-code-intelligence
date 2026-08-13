package com.java.semantic.syntax.application.concept;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceRecordComponentMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.domain.SourceTypeMetadata;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 從類別 metadata 投影 TYPE、METHOD、FIELD 與 ANNOTATION_USAGE 概念 */
public final class DeclarationConceptProvider implements ConceptProvider {

    private static final String PROVIDER_ID = "declaration";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<ConceptKind> supportedKinds() {
        return Set.copyOf(EnumSet.of(
                ConceptKind.TYPE, ConceptKind.METHOD, ConceptKind.FIELD, ConceptKind.ANNOTATION_USAGE));
    }

    @Override
    public ConceptProviderProjection project(RepositorySyntax syntax) {
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            SourceTypeIdentity sourceType = metadata.declaration().identity();
            String fullyQualifiedName = sourceType.fullyQualifiedName();
            String packageName = sourceType.javaType().packageName();
            String className = sourceType.javaType().className();
            TypeConceptIdentity typeIdentity = new TypeConceptIdentity(sourceType);
            TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(sourceType);
            entries.add(entry(typeIdentity, className, packageName,
                    Optional.of(fullyQualifiedName), ConceptAuthority.SYNTAX_DECLARED, Optional.empty(), Optional.empty()));
            addAnnotationEntries(entries, typeSubject, metadata.frameworkFacts().annotations(),
                    typeSubject.displayValue(), packageName, Optional.of(fullyQualifiedName));
            for (SourceFieldMetadata field : metadata.members().fields()) {
                TypeMember fieldMember = new TypeMember(sourceType, field.name());
                FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(fieldMember);
                FieldDeclarationSubjectIdentity fieldSubject = new FieldDeclarationSubjectIdentity(fieldMember);
                entries.add(entry(fieldIdentity,
                        field.name(),
                        packageName,
                        Optional.of(fullyQualifiedName),
                        ConceptAuthority.SYNTAX_DECLARED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new FieldConceptDetails(field.typeReference()))));
                addAnnotationEntries(entries, fieldSubject, field.annotationEvidence(),
                        fieldSubject.displayValue() + ":" + field.type(),
                        packageName, Optional.of(fullyQualifiedName));
            }
            for (SourceRecordComponentMetadata component : metadata.members().recordComponents()) {
                TypeMember componentMember = new TypeMember(sourceType, component.name());
                FieldConceptIdentity componentIdentity = new FieldConceptIdentity(componentMember);
                FieldDeclarationSubjectIdentity componentSubject = new FieldDeclarationSubjectIdentity(componentMember);
                entries.add(entry(componentIdentity,
                        component.name(),
                        packageName,
                        Optional.of(fullyQualifiedName),
                        ConceptAuthority.SYNTAX_DECLARED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new FieldConceptDetails(component.typeReference()))));
                addAnnotationEntries(entries, componentSubject, component.annotationEvidence(),
                        componentSubject.displayValue() + ":" + component.type(),
                        packageName, Optional.of(fullyQualifiedName));
            }
            for (SourceMethodMetadata method : metadata.members().methods()) {
                DeclarationSubjectIdentity methodSubject = methodSubject(metadata, method);
                Optional<MethodTarget> resolvedTarget = resolvedTarget(method);
                if (resolvedTarget.isPresent()) {
                    MethodTarget target = resolvedTarget.orElseThrow();
                    MethodConceptIdentity methodIdentity = new MethodConceptIdentity(target);
                    entries.add(entry(methodIdentity,
                            className + "." + method.name(),
                            packageName,
                            Optional.of(fullyQualifiedName),
                            ConceptAuthority.SYNTAX_RESOLVED,
                            method.returnType().map(returnType -> returnType.writtenType()),
                            Optional.empty()));
                }
                addAnnotationEntries(entries, methodSubject, method.annotationEvidence(),
                        methodSubject.displayValue(), packageName, Optional.of(fullyQualifiedName));
            }
        }
        return new ConceptProviderProjection(entries, List.of());
    }

    static DeclarationSubjectIdentity methodSubject(SourceTypeMetadata metadata, SourceMethodMetadata method) {
        Optional<MethodTarget> resolvedTarget = resolvedTarget(method);
        if (resolvedTarget.isPresent()) {
            return new ResolvedMethodDeclarationSubjectIdentity(resolvedTarget.orElseThrow());
        }
        return new UnresolvedMethodDeclarationSubjectIdentity(
                metadata.declaration().identity(),
                new MethodDeclarationSignature(method.name(), method.paramTypes()));
    }

    static Optional<MethodTarget> resolvedTarget(SourceMethodMetadata method) {
        if (method.analysisTarget().status() == AnalysisTargetStatus.RESOLVED) {
            return method.analysisTarget().target();
        }
        return Optional.empty();
    }

    private static void addAnnotationEntries(
            List<ConceptCatalogEntry> entries,
            DeclarationSubjectIdentity subject,
            List<AnnotationEvidence> annotationEvidence,
            String declarationDisplayValue,
            String packageName,
        Optional<String> declaringType) {
        for (AnnotationEvidence annotation : annotationEvidence) {
            boolean resolved = annotation.resolvedType().isPresent();
            AnnotationIdentity annotationIdentity = resolved
                    ? new ResolvedAnnotationIdentity(annotation.resolvedType().orElseThrow())
                    : new UnresolvedAnnotationIdentity(annotation.writtenName());
            AnnotationUsageConceptIdentity identity = new AnnotationUsageConceptIdentity(
                    subject, annotationIdentity);
            entries.add(entry(identity,
                    annotation.writtenName() + " on " + declarationDisplayValue,
                    packageName,
                    declaringType,
                    resolved ? ConceptAuthority.SYNTAX_RESOLVED : ConceptAuthority.WRITTEN_NAME_FALLBACK,
                    Optional.empty(),
                    Optional.of(annotation.writtenName())));
        }
    }

    static ConceptCatalogEntry entry(
            ConceptIdentity identity,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Optional<String> methodReturnType,
            Optional<String> annotationWrittenName) {
        return entry(
                identity,
                displayValue,
                packageName,
                declaringType,
                authority,
                methodReturnType,
                annotationWrittenName,
                Optional.empty());
    }

    static ConceptCatalogEntry entry(
            ConceptIdentity identity,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Optional<String> methodReturnType,
            Optional<String> annotationWrittenName,
            Optional<FieldConceptDetails> fieldDetails) {
        return new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                displayValue,
                packageName,
                declaringType,
                authority,
                Set.of(identity),
                methodReturnType,
                annotationWrittenName,
                Optional.empty(),
                fieldDetails);
    }

}
