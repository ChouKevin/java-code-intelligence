package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 判定來源宣告是否可作為方法實作探索的 framework-neutral 純規則 */
public final class MethodImplementationEligibilityPolicy {

    private MethodImplementationEligibilityPolicy() {
    }

    /** 僅 abstract interface 或 abstract class 的不可執行宣告可探索其實作 */
    public static boolean isEligible(SourceTypeMetadata ownerType, SourceMethodMetadata declaration) {
        SourceTypeMetadata type = Objects.requireNonNull(ownerType, "ownerType is required");
        SourceMethodMetadata method = Objects.requireNonNull(declaration, "declaration is required");
        boolean abstractDeclaration = method.abstractDeclaration() && !method.executableDeclaration();
        return abstractDeclaration && (SourceTypeKind.INTERFACE.equals(type.declaration().kind())
                || (SourceTypeKind.CLASS.equals(type.declaration().kind()) && type.declaration().abstractType()));
    }
}
