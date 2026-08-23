package com.java.semantic.syntax.domain;

import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;

/** 原始碼型別寫法與已證實 binding 的密封證據介面 */
public sealed interface TypeReference permits NominalTypeReference, PrimitiveTypeReference, ArrayTypeReference,
        WildcardTypeReference, TypeVariableReference, CompositeTypeReference, InferredTypeReference {

    String writtenType();

    Optional<JavaTypeIdentity> resolvedNamedType();

    default Optional<String> resolvedTypeName() {
        return resolvedNamedType().map(JavaTypeIdentity::fullyQualifiedName);
    }

    boolean sourceDefined();
}
