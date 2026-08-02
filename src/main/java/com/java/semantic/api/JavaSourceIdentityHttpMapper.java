package com.java.semantic.api;

import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity;

import java.util.Objects;

/** 集中轉換 Java、來源型別與方法的領域 identity 及 HTTP payload */
public final class JavaSourceIdentityHttpMapper {

    private JavaSourceIdentityHttpMapper() {
    }

    public static JavaTypeIdentityPayload toPayload(JavaTypeIdentity identity) {
        JavaTypeIdentity javaType = Objects.requireNonNull(identity, "identity is required");
        return new JavaTypeIdentityPayload(javaType.packageName(), javaType.className());
    }

    public static SourceTypeIdentityPayload toPayload(SourceTypeIdentity identity) {
        SourceTypeIdentity sourceType = Objects.requireNonNull(identity, "identity is required");
        return new SourceTypeIdentityPayload(toPayload(sourceType.javaType()), sourceType.sourceFile());
    }

    public static MethodTargetPayload toPayload(MethodTarget identity) {
        MethodTarget target = Objects.requireNonNull(identity, "identity is required");
        return new MethodTargetPayload(toPayload(target.sourceType()), target.methodName(), target.parameterTypes());
    }

    public static SourceMemberIdentityPayload toPayload(
            SourceMemberIdentity identity,
            SourceLocationHttpMapper sourceLocationMapper) {
        SourceMemberIdentity member = Objects.requireNonNull(identity, "identity is required");
        SourceLocationHttpMapper locationMapper = Objects.requireNonNull(
                sourceLocationMapper, "sourceLocationMapper is required");
        return switch (member) {
            case SourceMemberIdentity.TypeMember typeMember -> new SourceMemberIdentityPayload.TypeMember(
                    toPayload(typeMember.ownerType()), typeMember.name());
            case SourceMemberIdentity.MethodScoped methodScoped -> new SourceMemberIdentityPayload.MethodScoped(
                    toPayload(methodScoped.declaringMethod()),
                    locationMapper.toTextRange(methodScoped.declarationRange()),
                    methodScoped.name());
        };
    }

    public static JavaTypeIdentity toDomain(JavaTypeIdentityPayload payload) {
        JavaTypeIdentityPayload javaType = Objects.requireNonNull(payload, "payload is required");
        return new JavaTypeIdentity(javaType.packageName(), javaType.className());
    }

    public static SourceTypeIdentity toDomain(SourceTypeIdentityPayload payload) {
        SourceTypeIdentityPayload sourceType = Objects.requireNonNull(payload, "payload is required");
        return new SourceTypeIdentity(toDomain(sourceType.javaType()), sourceType.sourceFile());
    }

    public static MethodTarget toDomain(MethodTargetPayload payload) {
        MethodTargetPayload target = Objects.requireNonNull(payload, "payload is required");
        return new MethodTarget(toDomain(target.sourceType()), target.methodName(), target.parameterTypes());
    }

    public static SourceMemberIdentity toDomain(
            SourceMemberIdentityPayload payload,
            SourceLocationHttpMapper sourceLocationMapper) {
        SourceMemberIdentityPayload member = Objects.requireNonNull(payload, "payload is required");
        SourceLocationHttpMapper locationMapper = Objects.requireNonNull(
                sourceLocationMapper, "sourceLocationMapper is required");
        return switch (member) {
            case SourceMemberIdentityPayload.TypeMember typeMember -> new SourceMemberIdentity.TypeMember(
                    toDomain(typeMember.ownerType()), typeMember.name());
            case SourceMemberIdentityPayload.MethodScoped methodScoped -> new SourceMemberIdentity.MethodScoped(
                    toDomain(methodScoped.declaringMethod()),
                    locationMapper.toSyntaxRange(methodScoped.declarationRange()),
                    methodScoped.name());
        };
    }
}
