package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** annotation 與型別使用所屬宣告的封閉 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = DeclarationSubjectResponse.TypeDeclarationSubjectResponse.class, name = "TYPE"),
        @JsonSubTypes.Type(value = DeclarationSubjectResponse.ResolvedMethodDeclarationSubjectResponse.class, name = "METHOD"),
        @JsonSubTypes.Type(value = DeclarationSubjectResponse.UnresolvedMethodDeclarationSubjectResponse.class, name = "METHOD_UNRESOLVED"),
        @JsonSubTypes.Type(value = DeclarationSubjectResponse.FieldDeclarationSubjectResponse.class, name = "FIELD")
})
public sealed interface DeclarationSubjectResponse permits
        DeclarationSubjectResponse.TypeDeclarationSubjectResponse,
        DeclarationSubjectResponse.ResolvedMethodDeclarationSubjectResponse,
        DeclarationSubjectResponse.UnresolvedMethodDeclarationSubjectResponse,
        DeclarationSubjectResponse.FieldDeclarationSubjectResponse {

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown declaration subject property");
    }

    /** 型別宣告 subject 回應 */
    record TypeDeclarationSubjectResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType)
            implements DeclarationSubjectResponse {
    }

    /** 已解析方法宣告 subject 回應 */
    record ResolvedMethodDeclarationSubjectResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target)
            implements DeclarationSubjectResponse {
    }

    /** 未解析方法宣告 subject 回應 */
    record UnresolvedMethodDeclarationSubjectResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target)
            implements DeclarationSubjectResponse {
    }

    /** 欄位宣告 subject 回應 */
    record FieldDeclarationSubjectResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) SourceMemberIdentityPayload identity)
            implements DeclarationSubjectResponse {
    }
}
