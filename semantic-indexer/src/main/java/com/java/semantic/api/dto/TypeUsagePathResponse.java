package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 巢狀型別使用路徑節點的封閉 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TypeUsagePathResponse.TypeArgumentPathResponse.class, name = "TYPE_ARGUMENT"),
        @JsonSubTypes.Type(value = TypeUsagePathResponse.WildcardExtendsBoundPathResponse.class, name = "WILDCARD_EXTENDS_BOUND"),
        @JsonSubTypes.Type(value = TypeUsagePathResponse.WildcardSuperBoundPathResponse.class, name = "WILDCARD_SUPER_BOUND"),
        @JsonSubTypes.Type(value = TypeUsagePathResponse.TypeVariableBoundPathResponse.class, name = "TYPE_VARIABLE_BOUND")
})
public sealed interface TypeUsagePathResponse permits
        TypeUsagePathResponse.TypeArgumentPathResponse,
        TypeUsagePathResponse.WildcardExtendsBoundPathResponse,
        TypeUsagePathResponse.WildcardSuperBoundPathResponse,
        TypeUsagePathResponse.TypeVariableBoundPathResponse {

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown type usage path property");
    }

    /** 型別參數路徑節點 */
    record TypeArgumentPathResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) int index) implements TypeUsagePathResponse {
    }

    /** wildcard extends 路徑節點 */
    record WildcardExtendsBoundPathResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind) implements TypeUsagePathResponse {
    }

    /** wildcard super 路徑節點 */
    record WildcardSuperBoundPathResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind) implements TypeUsagePathResponse {
    }

    /** 型別變數上界路徑節點 */
    record TypeVariableBoundPathResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) int index) implements TypeUsagePathResponse {
    }
}
