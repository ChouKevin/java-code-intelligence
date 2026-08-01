package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

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
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String className)
            implements DeclarationSubjectResponse {
    }

    /** 已解析方法宣告 subject 回應 */
    record ResolvedMethodDeclarationSubjectResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target)
            implements DeclarationSubjectResponse {
    }

    /** 未解析方法宣告 subject 回應 */
    record UnresolvedMethodDeclarationSubjectResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerPackageName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerClassName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String methodName,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> parameterTypes)
            implements DeclarationSubjectResponse {
    }

    /** 欄位宣告 subject 回應 */
    record FieldDeclarationSubjectResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerPackageName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerClassName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String fieldName)
            implements DeclarationSubjectResponse {
    }
}
