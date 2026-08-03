package com.java.semantic.mcp.dto.concept;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.JavaType;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.Method;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceMember;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceType;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads.Statement;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads.StatementKey;
import com.java.semantic.mcp.dto.identity.TypeMemberScope;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Optional;

/** concept resolve MCP 查詢的封閉 typed identity */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.TypeIdentity.class, name = "TYPE"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.MethodIdentity.class, name = "METHOD"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.FieldIdentity.class, name = "FIELD"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.AnnotationUsageIdentity.class, name = "ANNOTATION_USAGE"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.TypeUsageIdentity.class, name = "TYPE_USAGE"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.ApiRouteIdentity.class, name = "API_ROUTE"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.MqDestinationIdentity.class, name = "MQ_DESTINATION"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.ScheduleIdentity.class, name = "SCHEDULE"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.MapperStatementIdentity.class, name = "MAPPER_STATEMENT"),
        @JsonSubTypes.Type(value = McpConceptIdentityPayload.MapperStatementVariantIdentity.class, name = "MAPPER_STATEMENT_VARIANT")
})
public sealed interface McpConceptIdentityPayload permits
        McpConceptIdentityPayload.TypeIdentity,
        McpConceptIdentityPayload.MethodIdentity,
        McpConceptIdentityPayload.FieldIdentity,
        McpConceptIdentityPayload.AnnotationUsageIdentity,
        McpConceptIdentityPayload.TypeUsageIdentity,
        McpConceptIdentityPayload.ApiRouteIdentity,
        McpConceptIdentityPayload.MqDestinationIdentity,
        McpConceptIdentityPayload.ScheduleIdentity,
        McpConceptIdentityPayload.MapperStatementIdentity,
        McpConceptIdentityPayload.MapperStatementVariantIdentity {

    /** 型別概念 identity */
    record TypeIdentity(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType sourceType)
            implements McpConceptIdentityPayload {
    }

    /** 方法概念 identity */
    record MethodIdentity(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Method target)
            implements McpConceptIdentityPayload {
    }

    /** 欄位概念 identity */
    record FieldIdentity(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid @TypeMemberScope SourceMember field)
            implements McpConceptIdentityPayload {
    }

    /** annotation 使用概念 identity */
    record AnnotationUsageIdentity(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid DeclarationSubject declaration,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Annotation annotationType)
            implements McpConceptIdentityPayload {
    }

    /** 型別使用概念 identity */
    record TypeUsageIdentity(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid DeclarationSubject owner,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid TypeUsageLocation location,
            @MonitoringField(MonitoringMode.NESTED) @NotNull List<@NotNull @Valid TypeUsagePath> path,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid ReferencedType referencedType)
            implements McpConceptIdentityPayload {
    }

    /** API route entry-point identity */
    record ApiRouteIdentity(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Method target,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String httpVerb,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String route)
            implements McpConceptIdentityPayload {
    }

    /** 訊息目的地 entry-point identity */
    record MqDestinationIdentity(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Method target,
            @MonitoringField(MonitoringMode.VALUE) @NotNull MqBroker broker,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String destination)
            implements McpConceptIdentityPayload {
    }

    /** 排程 entry-point identity */
    record ScheduleIdentity(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Method target,
            @MonitoringField(MonitoringMode.VALUE) @NotNull ScheduleTriggerKind triggerKind,
            @MonitoringField(MonitoringMode.VALUE) Optional<String> triggerValue)
            implements McpConceptIdentityPayload {

        public ScheduleIdentity {
            triggerValue = Optional.ofNullable(triggerValue).orElse(Optional.empty());
        }
    }

    /** 邏輯 mapper statement 概念 identity */
    record MapperStatementIdentity(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid StatementKey identity)
            implements McpConceptIdentityPayload {
    }

    /** 實體 mapper statement 證據概念 identity */
    record MapperStatementVariantIdentity(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid Statement identity)
            implements McpConceptIdentityPayload {
    }

    /** usage identity 的封閉宣告 subject */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = DeclarationSubject.Type.class, name = "TYPE"),
            @JsonSubTypes.Type(value = DeclarationSubject.Method.class, name = "METHOD"),
            @JsonSubTypes.Type(value = DeclarationSubject.UnresolvedMethod.class, name = "METHOD_UNRESOLVED"),
            @JsonSubTypes.Type(value = DeclarationSubject.Field.class, name = "FIELD")
    })
    sealed interface DeclarationSubject permits
            DeclarationSubject.Type,
            DeclarationSubject.Method,
            DeclarationSubject.UnresolvedMethod,
            DeclarationSubject.Field {

        /** 型別宣告 subject */
        record Type(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType sourceType)
                implements DeclarationSubject {
        }

        /** 已解析方法宣告 subject */
        record Method(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid McpJavaIdentityPayloads.Method target)
                implements DeclarationSubject {
        }

        /** 未解析方法宣告 subject */
        record UnresolvedMethod(
                @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceType owner,
                @MonitoringField(MonitoringMode.VALUE) @NotBlank String methodName,
                @MonitoringField(MonitoringMode.SIZE) @NotNull List<@NotBlank String> parameterTypes)
                implements DeclarationSubject {
        }

        /** 欄位宣告 subject */
        record Field(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid @TypeMemberScope SourceMember field)
                implements DeclarationSubject {
        }
    }

    /** annotation identity 的封閉變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Annotation.Resolved.class, name = "RESOLVED"),
            @JsonSubTypes.Type(value = Annotation.Unresolved.class, name = "UNRESOLVED")
    })
    sealed interface Annotation permits Annotation.Resolved, Annotation.Unresolved {

        /** 已解析 annotation identity */
        record Resolved(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid JavaType javaType)
                implements Annotation {
        }

        /** 未解析 annotation identity */
        record Unresolved(@MonitoringField(MonitoringMode.VALUE) @NotBlank String writtenName)
                implements Annotation {
        }
    }

    /** 型別使用位置 */
    record TypeUsageLocation(
            @MonitoringField(MonitoringMode.VALUE) @NotNull TypeUsageSlot slot,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer index) {
    }

    /** 型別使用路徑的封閉節點 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TypeUsagePath.TypeArgument.class, name = "TYPE_ARGUMENT"),
            @JsonSubTypes.Type(value = TypeUsagePath.WildcardExtendsBound.class, name = "WILDCARD_EXTENDS_BOUND"),
            @JsonSubTypes.Type(value = TypeUsagePath.WildcardSuperBound.class, name = "WILDCARD_SUPER_BOUND"),
            @JsonSubTypes.Type(value = TypeUsagePath.TypeVariableBound.class, name = "TYPE_VARIABLE_BOUND")
    })
    sealed interface TypeUsagePath permits
            TypeUsagePath.TypeArgument,
            TypeUsagePath.WildcardExtendsBound,
            TypeUsagePath.WildcardSuperBound,
            TypeUsagePath.TypeVariableBound {

        /** 型別參數路徑 */
        record TypeArgument(@MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer index)
                implements TypeUsagePath {
        }

        /** extends 上界路徑 */
        record WildcardExtendsBound() implements TypeUsagePath {
        }

        /** super 下界路徑 */
        record WildcardSuperBound() implements TypeUsagePath {
        }

        /** 型別變數上界路徑 */
        record TypeVariableBound(@MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer index)
                implements TypeUsagePath {
        }
    }

    /** 已解析引用型別 identity */
    record ReferencedType(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid JavaType javaType,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer arrayDimensions) {
    }
}
