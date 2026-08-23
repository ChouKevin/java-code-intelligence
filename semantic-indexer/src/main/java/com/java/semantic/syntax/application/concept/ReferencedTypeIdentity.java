package com.java.semantic.syntax.application.concept;

import java.util.Objects;

import com.java.semantic.identity.JavaTypeIdentity;

/** TYPE_USAGE 所引用的已解析 Java 型別與陣列維度 */
public record ReferencedTypeIdentity(JavaTypeIdentity javaType, int arrayDimensions) {

    public ReferencedTypeIdentity {
        javaType = Objects.requireNonNull(javaType, "javaType is required");
        if (arrayDimensions < 0) {
            throw new IllegalArgumentException("arrayDimensions must not be negative");
        }
    }

    /** 保留既有 canonical 與 transport 顯示的完整型別名稱 */
    public String fullyQualifiedName() {
        return javaType.fullyQualifiedName() + "[]".repeat(arrayDimensions);
    }
}
