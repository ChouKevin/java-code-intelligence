package com.java.semantic.syntax.domain;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;

import java.util.List;
import java.util.Optional;

/** 以最終來源型別模型建立測試證據 */
public final class SourceTypeMetadataFixture {

    private SourceTypeMetadataFixture() {
    }

    public static SourceTypeMetadata sourceType(
            String className,
            String packageName,
            String fullyQualifiedName,
            String sourceFile,
            SourceTypeKind kind,
            boolean abstractType,
            List<String> implementedTypes,
            List<String> extendedTypes,
            List<String> annotationNames,
            List<String> imports,
            List<SourceFieldMetadata> fields,
            List<SourceMethodMetadata> methods,
            boolean fluentSetters,
            boolean chainedAccessors,
            List<String> profiles,
            SyntaxRange range,
            SourceRange declarationLocation,
            boolean primary,
            List<String> beanQualifiers) {
        JavaTypeIdentity javaType = new JavaTypeIdentity(packageName, className);
        if (!javaType.fullyQualifiedName().equals(fullyQualifiedName)) {
            throw new IllegalArgumentException("fullyQualifiedName must match packageName and className");
        }
        return new SourceTypeMetadata(
                new SourceTypeDeclaration(new SourceTypeIdentity(javaType, sourceFile), kind, abstractType, declarationLocation),
                new SourceTypeRelationships(nominalTypes(extendedTypes), nominalTypes(implementedTypes)),
                new SourceTypeMembers(fields, methods, fluentSetters, chainedAccessors),
                new FrameworkTypeFacts(annotationNames.stream()
                        .map(name -> new AnnotationEvidence(name, Optional.empty()))
                        .toList(), profiles, primary, beanQualifiers),
                new CompilationUnitContext(imports));
    }

    public static SourceTypeMetadata sourceType(MethodTarget target, List<SourceMethodMetadata> methods) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(1, 0));
        return sourceType(
                target.className(),
                target.packageName(),
                target.fullyQualifiedClassName(),
                target.sourceFile(),
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                methods,
                false,
                false,
                List.of(),
                range,
                new SourceRange(target.sourceFile(), range),
                false,
                List.of());
    }

    private static List<NominalTypeReference> nominalTypes(List<String> writtenTypes) {
        return writtenTypes.stream()
                .map(name -> new NamedTypeReference(name, name, Optional.empty(), false))
                .map(NominalTypeReference.class::cast)
                .toList();
    }

}
