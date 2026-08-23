package com.java.semantic.identity;

import java.util.Objects;

/** 儲存庫來源已證實的 Java 型別識別 */
public record SourceTypeIdentity(JavaTypeIdentity javaType, String sourceFile) {

    public SourceTypeIdentity {
        javaType = Objects.requireNonNull(javaType, "javaType is required");
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
    }

    public String fullyQualifiedName() {
        return javaType.fullyQualifiedName();
    }
}
