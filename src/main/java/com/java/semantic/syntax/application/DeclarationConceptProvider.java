package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.springframework.util.StringUtils;

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
        for (ClassMetadata metadata : syntax.classes()) {
            TypeConceptIdentity typeIdentity = new TypeConceptIdentity(metadata.sourceFile(), metadata.fullyQualifiedName());
            TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(
                    metadata.sourceFile(), metadata.fullyQualifiedName());
            entries.add(entry(typeIdentity, metadata.fullyQualifiedName(), metadata.className(), metadata.packageName(),
                    Optional.of(metadata.fullyQualifiedName()), ConceptAuthority.SYNTAX_DECLARED,
                    metadata.fullyQualifiedName()));
            addAnnotationEntries(entries, metadata.sourceFile(), typeSubject, metadata.annotations(),
                    metadata.annotationEvidence(), metadata.packageName(), Optional.of(metadata.fullyQualifiedName()));
            for (FieldInfo field : metadata.fields()) {
                FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(
                        metadata.sourceFile(), metadata.fullyQualifiedName(), field.name(), field.type());
                FieldDeclarationSubjectIdentity fieldSubject = new FieldDeclarationSubjectIdentity(
                        metadata.sourceFile(), metadata.fullyQualifiedName(), field.name(), field.type());
                entries.add(entry(fieldIdentity,
                        sourceQualified(metadata.sourceFile(), fieldSubject.displayValue()),
                        field.name(),
                        metadata.packageName(),
                        Optional.of(metadata.fullyQualifiedName()),
                        ConceptAuthority.SYNTAX_DECLARED,
                        metadata.fullyQualifiedName() + " " + field.name() + " " + field.type()));
                addAnnotationEntries(entries, metadata.sourceFile(), fieldSubject, field.annotations(),
                        field.annotationEvidence(), metadata.packageName(), Optional.of(metadata.fullyQualifiedName()));
            }
            for (MethodSignature method : metadata.methods()) {
                DeclarationSubjectIdentity methodSubject = methodSubject(metadata, method);
                Optional<MethodTarget> resolvedTarget = resolvedTarget(method);
                if (resolvedTarget.isPresent()) {
                    MethodTarget target = resolvedTarget.orElseThrow();
                    MethodConceptIdentity methodIdentity = new MethodConceptIdentity(target);
                    entries.add(entry(methodIdentity,
                            sourceQualified(target.sourceFile(), ConceptIdentitySupport.methodDisplayValue(target)),
                            metadata.className() + "." + method.name(),
                            metadata.packageName(),
                            Optional.of(metadata.fullyQualifiedName()),
                            ConceptAuthority.SYNTAX_RESOLVED,
                            metadata.fullyQualifiedName() + " " + method.name() + " "
                                    + String.join(" ", method.paramTypes())));
                }
                addAnnotationEntries(entries, metadata.sourceFile(), methodSubject, method.annotations(),
                        method.annotationEvidence(), metadata.packageName(), Optional.of(metadata.fullyQualifiedName()));
            }
        }
        return new ConceptProviderProjection(entries, List.of());
    }

    static DeclarationSubjectIdentity methodSubject(ClassMetadata metadata, MethodSignature method) {
        Optional<MethodTarget> resolvedTarget = resolvedTarget(method);
        if (resolvedTarget.isPresent()) {
            return new ResolvedMethodDeclarationSubjectIdentity(resolvedTarget.orElseThrow());
        }
        return new UnresolvedMethodDeclarationSubjectIdentity(
                metadata.sourceFile(), metadata.fullyQualifiedName(), method.name(), method.paramTypes());
    }

    static Optional<MethodTarget> resolvedTarget(MethodSignature method) {
        if (method.analysisTarget().status() == AnalysisTargetStatus.RESOLVED) {
            return method.analysisTarget().target();
        }
        return Optional.empty();
    }

    static String resolvedTypeName(ResolvedTypeIdentity resolvedType) {
        return resolvedType.packageName().isEmpty()
                ? resolvedType.className()
                : resolvedType.packageName() + "." + resolvedType.className();
    }

    private static void addAnnotationEntries(
            List<ConceptCatalogEntry> entries,
            String sourceFile,
            DeclarationSubjectIdentity subject,
            List<String> writtenAnnotations,
            List<AnnotationEvidence> annotationEvidence,
            String packageName,
            Optional<String> declaringType) {
        List<AnnotationEvidence> annotations = annotationEvidence.isEmpty()
                ? writtenAnnotations.stream().map(writtenName -> new AnnotationEvidence(writtenName, Optional.empty())).toList()
                : annotationEvidence;
        for (AnnotationEvidence annotation : annotations) {
            boolean resolved = annotation.resolvedType().isPresent();
            String annotationIdentity = resolved
                    ? resolvedTypeName(annotation.resolvedType().orElseThrow())
                    : annotation.writtenName();
            AnnotationUsageConceptIdentity identity = new AnnotationUsageConceptIdentity(
                    sourceFile, subject, annotationIdentity);
            entries.add(entry(identity,
                    annotationIdentity + "@" + subject.displayValue(),
                    annotation.writtenName() + " on " + subject.displayValue(),
                    packageName,
                    declaringType,
                    resolved ? ConceptAuthority.SYNTAX_RESOLVED : ConceptAuthority.WRITTEN_NAME_FALLBACK,
                    annotationIdentity + " " + subject.displayValue()));
        }
    }

    static ConceptCatalogEntry entry(
            ConceptIdentity identity,
            String canonicalValue,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            String tokenSource) {
        return new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                canonicalValue,
                displayValue,
                ConceptSearchTokenizer.tokenize(tokenSource),
                packageName,
                declaringType,
                authority,
                Set.of(identity));
    }

    static String sourceQualified(String sourceFile, String value) {
        return sourceFile + "::" + value;
    }
}
