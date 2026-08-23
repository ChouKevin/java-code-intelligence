package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/** 內部 reference 查詢可接受的封閉 exact target */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = InternalSourceReferenceTargetPayload.Type.class, name = "TYPE"),
        @JsonSubTypes.Type(value = InternalSourceReferenceTargetPayload.Method.class, name = "METHOD"),
        @JsonSubTypes.Type(value = InternalSourceReferenceTargetPayload.Member.class, name = "MEMBER")
})
public sealed interface InternalSourceReferenceTargetPayload permits
        InternalSourceReferenceTargetPayload.Type,
        InternalSourceReferenceTargetPayload.Method,
        InternalSourceReferenceTargetPayload.Member {

    String kind();

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown internal reference target property");
    }

    /** 精確來源型別 target */
    record Type(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceTypeIdentityPayload identity)
            implements InternalSourceReferenceTargetPayload {

        public Type {
            if (!"TYPE".equals(kind)) {
                throw new IllegalArgumentException("kind must be TYPE");
            }
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 精確 canonical 方法 target */
    record Method(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTargetPayload identity)
            implements InternalSourceReferenceTargetPayload {

        public Method {
            if (!"METHOD".equals(kind)) {
                throw new IllegalArgumentException("kind must be METHOD");
            }
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 精確型別或方法範圍成員 target */
    record Member(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceMemberIdentityPayload identity)
            implements InternalSourceReferenceTargetPayload {

        public Member {
            if (!"MEMBER".equals(kind)) {
                throw new IllegalArgumentException("kind must be MEMBER");
            }
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }
}
