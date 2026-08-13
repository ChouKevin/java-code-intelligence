package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SourceRange;

import java.util.List;
import java.util.Objects;

/** 帶來源宣告與精確 internal-reference follow-up 的 enum 常數成員 */
public record EnumConstantTypeMember(
        String constantName,
        SourceRange declarationLocation,
        List<String> annotations,
        List<DiscoveryFollowUp> availableFollowUps) implements TypeMember {

    public EnumConstantTypeMember {
        constantName = requiredText(constantName, "constantName");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps, "availableFollowUps are required"));
    }

    @Override
    public TypeMemberKind kind() {
        return TypeMemberKind.ENUM_CONSTANT;
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
