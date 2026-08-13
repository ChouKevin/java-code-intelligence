package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SourceRange;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 帶型別、來源宣告與精確 follow-up 的 record component 成員 */
public record RecordComponentTypeMember(
        String componentName,
        String writtenType,
        Optional<String> resolvedType,
        SourceRange declarationLocation,
        List<String> annotations,
        List<DiscoveryFollowUp> availableFollowUps) implements TypeMember {

    public RecordComponentTypeMember {
        componentName = requiredText(componentName, "componentName");
        writtenType = requiredText(writtenType, "writtenType");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps, "availableFollowUps are required"));
    }

    @Override
    public TypeMemberKind kind() {
        return TypeMemberKind.RECORD_COMPONENT;
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
