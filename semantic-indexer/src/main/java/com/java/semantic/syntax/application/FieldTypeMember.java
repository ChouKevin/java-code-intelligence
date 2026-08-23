package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 保留欄位原始型別、解析型別、註解與索引限制的欄位成員 */
public record FieldTypeMember(
        String fieldName,
        String writtenType,
        Optional<String> resolvedType,
        List<String> annotations,
        List<TypeMemberLimitation> limitations,
        List<DiscoveryFollowUp> availableFollowUps) implements TypeMember {

    public FieldTypeMember {
        fieldName = requiredText(fieldName, "fieldName");
        writtenType = requiredText(writtenType, "writtenType");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    @Override
    public TypeMemberKind kind() {
        return TypeMemberKind.FIELD;
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
