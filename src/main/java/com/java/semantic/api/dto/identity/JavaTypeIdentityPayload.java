package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** 不含儲存庫位置的 Java 名目型別 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JavaTypeIdentityPayload(
        @MonitoringField(MonitoringMode.VALUE) @NotNull String packageName,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*"
                + "(?:\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*)*")
        String className) {

    public JavaTypeIdentityPayload {
        packageName = Objects.requireNonNull(packageName, "packageName is required");
        className = Objects.requireNonNull(className, "className is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown Java type identity property");
    }
}
