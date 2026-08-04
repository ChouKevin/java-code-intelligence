package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** 型別直接成員或方法範圍宣告的封閉 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "scope")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SourceMemberIdentityPayload.TypeMember.class, name = "TYPE"),
        @JsonSubTypes.Type(value = SourceMemberIdentityPayload.MethodScoped.class, name = "METHOD")
})
public sealed interface SourceMemberIdentityPayload permits
        SourceMemberIdentityPayload.TypeMember,
        SourceMemberIdentityPayload.MethodScoped {

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown source member identity property");
    }

    /** 型別直接擁有的成員 identity */
    record TypeMember(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceTypeIdentityPayload ownerType,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
            @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                    + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*") String name)
            implements SourceMemberIdentityPayload {

        public TypeMember {
            ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
            name = Objects.requireNonNull(name, "name is required");
        }
    }

    /** 方法內以宣告範圍固定的成員 identity */
    record MethodScoped(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTargetPayload declaringMethod,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid TextRangePayload declarationRange,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
            @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                    + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*") String name)
            implements SourceMemberIdentityPayload {

        public MethodScoped {
            declaringMethod = Objects.requireNonNull(declaringMethod, "declaringMethod is required");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            name = Objects.requireNonNull(name, "name is required");
        }
    }
}
