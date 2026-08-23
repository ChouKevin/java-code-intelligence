package com.java.semantic.syntax.domain;

import com.java.semantic.identity.JavaTypeIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 來源型別方法的完整宣告、呼叫與分析目標證據 */
public record SourceMethodMetadata(
        String name,
        List<String> paramTypes,
        SqlSourceKind sqlSource,
        Optional<SourceRange> annotationSqlLocation,
        SourceRange declarationLocation,
        List<TypeReference> parameterTypeReferences,
        Optional<TypeReference> returnType,
        List<SyntaxInvocation> invocations,
        List<AnnotationEvidence> annotationEvidence,
        List<JavaTypeIdentity> bodyTypeReferences,
        SyntaxPosition namePosition,
        MethodTargetResolution analysisTarget,
        boolean executableDeclaration,
        boolean abstractDeclaration,
        boolean overridableDeclaration) {

    public SourceMethodMetadata {
        paramTypes = List.copyOf(paramTypes);
        annotationSqlLocation = Objects.requireNonNull(annotationSqlLocation, "annotationSqlLocation is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        parameterTypeReferences = List.copyOf(parameterTypeReferences);
        returnType = Objects.requireNonNull(returnType, "returnType is required");
        invocations = List.copyOf(invocations);
        annotationEvidence = List.copyOf(annotationEvidence);
        bodyTypeReferences = List.copyOf(bodyTypeReferences);
        namePosition = Objects.requireNonNull(namePosition, "namePosition is required");
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        if (sqlSource == SqlSourceKind.ANNOTATION && annotationSqlLocation.isEmpty()) {
            throw new IllegalArgumentException("annotation SQL requires annotationSqlLocation");
        }
        if (sqlSource != SqlSourceKind.ANNOTATION && annotationSqlLocation.isPresent()) {
            throw new IllegalArgumentException("annotationSqlLocation requires annotation SQL");
        }
        if (analysisTarget.target().isPresent()
                && !analysisTarget.target().orElseThrow().sourceFile().equals(declarationLocation.sourceFile())) {
            throw new IllegalArgumentException("analysis target sourceFile must match declarationLocation");
        }
    }

    /** 參數個數 */
    public int paramCount() {
        return paramTypes.size();
    }
}
