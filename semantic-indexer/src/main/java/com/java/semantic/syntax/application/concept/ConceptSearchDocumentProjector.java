package com.java.semantic.syntax.application.concept;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 從 typed identity 與 catalog details 導出唯一且不外洩的概念搜尋文件 */
public final class ConceptSearchDocumentProjector {

    /** 投影一筆 catalog 項目的搜尋 token，不解析 displayValue */
    public ConceptSearchDocument project(ConceptCatalogEntry entry) {
        ConceptCatalogEntry catalogEntry = Objects.requireNonNull(entry, "entry is required");
        Set<String> tokens = new LinkedHashSet<>();
        switch (catalogEntry.identity()) {
            case TypeConceptIdentity identity -> addType(tokens, identity.type().fullyQualifiedName());
            case MethodConceptIdentity identity -> {
                addMethod(tokens, identity.target());
                catalogEntry.methodReturnType().ifPresent(returnType -> addType(tokens, returnType));
            }
            case FieldConceptIdentity identity -> {
                addType(tokens, identity.field().ownerType().fullyQualifiedName());
                addValue(tokens, identity.field().name());
                catalogEntry.fieldDetails().ifPresent(details -> addType(tokens, details.declaredType().writtenType()));
            }
            case AnnotationUsageConceptIdentity identity -> {
                switch (identity.annotationIdentity()) {
                    case ResolvedAnnotationIdentity annotation -> addType(
                            tokens, annotation.javaType().fullyQualifiedName());
                    case UnresolvedAnnotationIdentity annotation -> addValue(tokens, annotation.writtenName());
                }
                catalogEntry.annotationWrittenName().ifPresent(writtenName -> addValue(tokens, writtenName));
                addSubject(tokens, identity.annotatedDeclaration());
            }
            case TypeUsageConceptIdentity identity -> {
                addType(tokens, identity.referencedType().fullyQualifiedName());
                addSubject(tokens, identity.owner());
            }
            case ApiRouteConceptIdentity identity -> {
                addValue(tokens, identity.httpVerb());
                addValue(tokens, identity.route());
                addMethod(tokens, identity.target());
            }
            case MqDestinationConceptIdentity identity -> {
                addValue(tokens, identity.broker().name());
                addValue(tokens, identity.destination());
                addMethod(tokens, identity.target());
            }
            case ScheduleConceptIdentity identity -> {
                addValue(tokens, identity.triggerKind().name());
                identity.triggerValue().ifPresent(trigger -> addValue(tokens, trigger));
                addMethod(tokens, identity.target());
            }
            case MapperStatementConceptIdentity identity -> {
                addType(tokens, identity.statementKey().namespace());
                addValue(tokens, identity.statementKey().statementId());
                catalogEntry.mapperStatementMapping().orElseThrow().targets()
                        .forEach(target -> addMethod(tokens, target));
            }
            case MapperConceptIdentity.MapperStatementVariantEvidenceIdentity ignored ->
                    throw new IllegalArgumentException("mapper variant evidence cannot be a catalog identity");
        }
        return new ConceptSearchDocument(tokens);
    }

    private static void addSubject(Set<String> tokens, DeclarationSubjectIdentity subject) {
        switch (subject) {
            case TypeDeclarationSubjectIdentity identity -> addType(tokens, identity.type().fullyQualifiedName());
            case ResolvedMethodDeclarationSubjectIdentity identity -> addMethod(tokens, identity.target());
            case UnresolvedMethodDeclarationSubjectIdentity identity -> {
                addType(tokens, identity.owner().fullyQualifiedName());
                addValue(tokens, identity.signature().methodName());
                identity.signature().parameterTypes().forEach(parameter -> addType(tokens, parameter));
            }
            case FieldDeclarationSubjectIdentity identity -> {
                addType(tokens, identity.field().ownerType().fullyQualifiedName());
                addValue(tokens, identity.field().name());
            }
        }
    }

    private static void addMethod(Set<String> tokens, MethodTarget target) {
        MethodTarget methodTarget = Objects.requireNonNull(target, "target is required");
        addType(tokens, methodTarget.fullyQualifiedClassName());
        addValue(tokens, methodTarget.methodName());
        methodTarget.parameterTypes().forEach(parameter -> addType(tokens, parameter));
    }

    private static void addType(Set<String> tokens, String typeName) {
        addValue(tokens, typeName);
        tokens.add(typeName.toLowerCase(Locale.ROOT));
        int delimiter = typeName.lastIndexOf('.');
        if (delimiter > 0) {
            addValue(tokens, typeName.substring(0, delimiter));
            addValue(tokens, typeName.substring(delimiter + 1));
        }
    }

    private static void addValue(Set<String> tokens, String value) {
        tokens.addAll(ConceptSearchTokenizer.tokenize(value));
    }
}
