package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/** 來源型別限定且保留參數順序的 canonical 方法 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MethodTargetPayload(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid SourceTypeIdentityPayload sourceType,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*")
        String methodName,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) @NotNull List<@NotBlank String> parameterTypes) {

    public MethodTargetPayload {
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        methodName = Objects.requireNonNull(methodName, "methodName is required");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown method target property");
    }
}
