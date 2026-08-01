package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/** Validated API input for one exact, source-qualified method target. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MethodTargetRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank
        @Size(max = 1024)
        @Pattern(regexp = "[^\\p{javaISOControl}]+")
        String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*"
                + "(?:\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*)*")
        String className,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*")
        String methodName,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) @NotNull List<@NotBlank String> parameterTypes) {

    public MethodTargetRequest {
        packageName = Objects.requireNonNull(packageName, "packageName is required");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown target property");
    }
}
