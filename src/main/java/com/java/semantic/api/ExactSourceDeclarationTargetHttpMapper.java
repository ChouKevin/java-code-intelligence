package com.java.semantic.api;

import com.java.semantic.api.dto.InternalSourceReferenceTargetPayload;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 在封閉 HTTP target 與 exact declaration identity 間集中轉換 */
@Component
public final class ExactSourceDeclarationTargetHttpMapper {

    private final SourceLocationHttpMapper sourceLocationMapper;

    public ExactSourceDeclarationTargetHttpMapper(SourceLocationHttpMapper sourceLocationMapper) {
        this.sourceLocationMapper = Objects.requireNonNull(
                sourceLocationMapper, "sourceLocationMapper is required");
    }

    public ExactSourceDeclarationTarget toDomain(InternalSourceReferenceTargetPayload payload) {
        InternalSourceReferenceTargetPayload target = Objects.requireNonNull(payload, "target is required");
        return switch (target) {
            case InternalSourceReferenceTargetPayload.Type type -> new ExactSourceDeclarationTarget.Type(
                    JavaSourceIdentityHttpMapper.toDomain(type.identity()));
            case InternalSourceReferenceTargetPayload.Method method -> new ExactSourceDeclarationTarget.Method(
                    JavaSourceIdentityHttpMapper.toDomain(method.identity()));
            case InternalSourceReferenceTargetPayload.Member member -> new ExactSourceDeclarationTarget.Member(
                    JavaSourceIdentityHttpMapper.toDomain(member.identity(), sourceLocationMapper));
        };
    }

    public InternalSourceReferenceTargetPayload toPayload(ExactSourceDeclarationTarget target) {
        ExactSourceDeclarationTarget declarationTarget = Objects.requireNonNull(target, "target is required");
        return switch (declarationTarget) {
            case ExactSourceDeclarationTarget.Type type -> new InternalSourceReferenceTargetPayload.Type(
                    "TYPE", JavaSourceIdentityHttpMapper.toPayload(type.identity()));
            case ExactSourceDeclarationTarget.Method method -> new InternalSourceReferenceTargetPayload.Method(
                    "METHOD", JavaSourceIdentityHttpMapper.toPayload(method.identity()));
            case ExactSourceDeclarationTarget.Member member -> new InternalSourceReferenceTargetPayload.Member(
                    "MEMBER", JavaSourceIdentityHttpMapper.toPayload(member.identity(), sourceLocationMapper));
        };
    }
}
