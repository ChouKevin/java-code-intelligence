package com.java.semantic.mcp.dto.identity;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/** MCP 專用 Java 與來源宣告 identity transport 集合 */
public final class McpJavaIdentityPayloads {

    private static final String JAVA_IDENTIFIER_PATTERN =
            "(?!.*\\p{javaIdentifierIgnorable})\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final String QUALIFIED_JAVA_IDENTIFIER_PATTERN =
            "(?!.*\\p{javaIdentifierIgnorable})\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
                    + "(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*";
    private static final String REPOSITORY_RELATIVE_SOURCE_PATTERN =
            "(?=.{1,1024}$)(?!/)(?!.*[\\\\:])(?!.*[\\p{javaWhitespace}\\p{Z}\\p{Cntrl}])"
                    + "(?!.*//)(?!.*(?:^|/)\\.{1,2}(?:/|$))(?!.*/$).+";

    private McpJavaIdentityPayloads() {
        throw new UnsupportedOperationException("utility class");
    }

    /** MCP Java 型別 identity */
    public record JavaType(
            @MonitoringField(MonitoringMode.VALUE) @NotNull String packageName,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
            @Pattern(regexp = QUALIFIED_JAVA_IDENTIFIER_PATTERN) String className) {
    }

    /** MCP 來源型別 identity */
    public record SourceType(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid JavaType javaType,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 1024)
            @Pattern(regexp = REPOSITORY_RELATIVE_SOURCE_PATTERN) String sourceFile) {
    }

    /** MCP canonical 方法 identity */
    public record Method(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType sourceType,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
            @Pattern(regexp = JAVA_IDENTIFIER_PATTERN) String methodName,
            @MonitoringField(MonitoringMode.SIZE) @NotNull List<@NotBlank String> parameterTypes) {
    }

    /** MCP 零基原始碼位置 */
    public record Position(
            @MonitoringField(MonitoringMode.VALUE) @Min(0) int line,
            @MonitoringField(MonitoringMode.VALUE) @Min(0) int character) {
    }

    /** MCP 半開原始碼範圍 */
    public record Range(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Position start,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Position end) {
    }

    /** MCP 封閉來源成員 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "scope")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SourceMember.TypeMember.class, name = "TYPE"),
            @JsonSubTypes.Type(value = SourceMember.MethodScoped.class, name = "METHOD")
    })
    public sealed interface SourceMember permits SourceMember.TypeMember, SourceMember.MethodScoped {

        /** MCP 型別直接成員 identity */
        record TypeMember(
                @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType ownerType,
                @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
                @Pattern(regexp = JAVA_IDENTIFIER_PATTERN) String name) implements SourceMember {
        }

        /** MCP 方法範圍成員 identity */
        record MethodScoped(
                @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Method declaringMethod,
                @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Range declarationRange,
                @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
                @Pattern(regexp = JAVA_IDENTIFIER_PATTERN) String name) implements SourceMember {
        }
    }

    /** 還原 MCP Java 型別 identity */
    public static JavaTypeIdentity toDomain(JavaType payload) {
        JavaType value = Objects.requireNonNull(payload, "payload is required");
        return new JavaTypeIdentity(value.packageName(), value.className());
    }

    /** 還原 MCP 來源型別 identity */
    public static SourceTypeIdentity toDomain(SourceType payload) {
        SourceType value = Objects.requireNonNull(payload, "payload is required");
        return new SourceTypeIdentity(toDomain(value.javaType()), value.sourceFile());
    }

    /** 還原 MCP canonical 方法 identity */
    public static MethodTarget toDomain(Method payload) {
        Method value = Objects.requireNonNull(payload, "payload is required");
        return new MethodTarget(toDomain(value.sourceType()), value.methodName(), value.parameterTypes());
    }

    /** 還原 MCP 原始碼範圍 */
    public static SyntaxRange toDomain(Range payload) {
        Range value = Objects.requireNonNull(payload, "payload is required");
        return new SyntaxRange(toDomain(value.start()), toDomain(value.end()));
    }

    /** 還原 MCP 來源成員 identity */
    public static SourceMemberIdentity toDomain(SourceMember payload) {
        SourceMember value = Objects.requireNonNull(payload, "payload is required");
        return switch (value) {
            case SourceMember.TypeMember typeMember -> new SourceMemberIdentity.TypeMember(
                    toDomain(typeMember.ownerType()), typeMember.name());
            case SourceMember.MethodScoped methodScoped -> new SourceMemberIdentity.MethodScoped(
                    toDomain(methodScoped.declaringMethod()),
                    toDomain(methodScoped.declarationRange()),
                    methodScoped.name());
        };
    }

    private static SyntaxPosition toDomain(Position payload) {
        Position value = Objects.requireNonNull(payload, "payload is required");
        return new SyntaxPosition(value.line(), value.character());
    }
}
