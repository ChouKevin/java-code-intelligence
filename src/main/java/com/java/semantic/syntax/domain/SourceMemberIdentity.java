package com.java.semantic.syntax.domain;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;

import java.util.Objects;

/** 來源型別成員或方法範圍宣告的 identity */
public sealed interface SourceMemberIdentity permits
        SourceMemberIdentity.TypeMember,
        SourceMemberIdentity.MethodScoped {

    String name();

    /** 型別直接擁有的欄位、component 或 enum constant identity */
    record TypeMember(SourceTypeIdentity ownerType, String name) implements SourceMemberIdentity {

        public TypeMember {
            ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
            name = requiredText(name, "name");
        }
    }

    /** 方法內參數或 local 以宣告範圍固定的 identity */
    record MethodScoped(
            MethodTarget declaringMethod,
            SyntaxRange declarationRange,
            String name) implements SourceMemberIdentity {

        public MethodScoped {
            declaringMethod = Objects.requireNonNull(declaringMethod, "declaringMethod is required");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            name = requiredText(name, "name");
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
