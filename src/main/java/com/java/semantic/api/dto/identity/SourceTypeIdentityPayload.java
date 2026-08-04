package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** 以儲存庫相對來源檔案限定的 Java 型別 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceTypeIdentityPayload(
        @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid JavaTypeIdentityPayload javaType,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank @Size(max = 1024)
        @Pattern(regexp = "[^\\p{javaISOControl}]+") String sourceFile) {

    public SourceTypeIdentityPayload {
        javaType = Objects.requireNonNull(javaType, "javaType is required");
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown source type identity property");
    }
}
