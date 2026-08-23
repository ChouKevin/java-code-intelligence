package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;

import java.util.List;
import java.util.Objects;

/** 帶完整 canonical MethodTarget 與方法分析 follow-up 的方法成員 */
public record MethodTypeMember(
        MethodTarget target,
        List<DiscoveryFollowUp> availableFollowUps) implements TypeMember {

    public MethodTypeMember {
        target = Objects.requireNonNull(target, "target is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    @Override
    public TypeMemberKind kind() {
        return TypeMemberKind.METHOD;
    }
}
