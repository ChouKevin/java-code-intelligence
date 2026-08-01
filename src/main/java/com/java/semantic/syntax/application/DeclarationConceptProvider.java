package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.JavaTypeIdentity;
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
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
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
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            String sourceFile = metadata.declaration().identity().sourceFile();
            String fullyQualifiedName = metadata.declaration().identity().fullyQualifiedName();
            String packageName = metadata.declaration().identity().javaType().packageName();
            String className = metadata.declaration().identity().javaType().className();
            TypeConceptIdentity typeIdentity = new TypeConceptIdentity(sourceFile, fullyQualifiedName);
            TypeDeclarationSubjectIdentity typeSubject = new TypeDeclarationSubjectIdentity(
                    sourceFile, fullyQualifiedName);
            entries.add(entry(typeIdentity, fullyQualifiedName, className, packageName,
                    Optional.of(fullyQualifiedName), ConceptAuthority.SYNTAX_DECLARED, fullyQualifiedName));
            addAnnotationEntries(entries, sourceFile, typeSubject, metadata.frameworkFacts().annotations(),
                    packageName, Optional.of(fullyQualifiedName));
            for (SourceFieldMetadata field : metadata.members().fields()) {
                FieldConceptIdentity fieldIdentity = new FieldConceptIdentity(
                        sourceFile, fullyQualifiedName, field.name(), field.type());
                FieldDeclarationSubjectIdentity fieldSubject = new FieldDeclarationSubjectIdentity(
                        sourceFile, fullyQualifiedName, field.name(), field.type());
                entries.add(entry(fieldIdentity,
                        sourceQualified(sourceFile, fieldSubject.displayValue()),
                        field.name(),
                        packageName,
                        Optional.of(fullyQualifiedName),
                        ConceptAuthority.SYNTAX_DECLARED,
                        fullyQualifiedName + " " + field.name() + " " + field.type()));
                addAnnotationEntries(entries, sourceFile, fieldSubject, field.annotationEvidence(),
                        packageName, Optional.of(fullyQualifiedName));
            }
            for (SourceMethodMetadata method : metadata.members().methods()) {
                DeclarationSubjectIdentity methodSubject = methodSubject(metadata, method);
                Optional<MethodTarget> resolvedTarget = resolvedTarget(method);
                if (resolvedTarget.isPresent()) {
                    MethodTarget target = resolvedTarget.orElseThrow();
                    MethodConceptIdentity methodIdentity = new MethodConceptIdentity(target);
                    entries.add(entry(methodIdentity,
                            sourceQualified(target.sourceFile(), ConceptIdentitySupport.methodDisplayValue(target)),
                            className + "." + method.name(),
                            packageName,
                            Optional.of(fullyQualifiedName),
                            ConceptAuthority.SYNTAX_RESOLVED,
                            fullyQualifiedName + " " + method.name() + " "
                                    + String.join(" ", method.paramTypes())));
                }
                addAnnotationEntries(entries, sourceFile, methodSubject, method.annotationEvidence(),
                        packageName, Optional.of(fullyQualifiedName));
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
                metadata.declaration().identity().sourceFile(), metadata.declaration().identity().fullyQualifiedName(),
                method.name(), method.paramTypes());
    }

    static Optional<MethodTarget> resolvedTarget(SourceMethodMetadata method) {
        if (method.analysisTarget().status() == AnalysisTargetStatus.RESOLVED) {
            return method.analysisTarget().target();
        }
        return Optional.empty();
    }

    static String resolvedTypeName(JavaTypeIdentity resolvedType) {
        return resolvedType.packageName().isEmpty()
                ? resolvedType.className()
                : resolvedType.packageName() + "." + resolvedType.className();
    }

    private static void addAnnotationEntries(
            List<ConceptCatalogEntry> entries,
            String sourceFile,
            DeclarationSubjectIdentity subject,
            List<AnnotationEvidence> annotationEvidence,
            String packageName,
            Optional<String> declaringType) {
        for (AnnotationEvidence annotation : annotationEvidence) {
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
