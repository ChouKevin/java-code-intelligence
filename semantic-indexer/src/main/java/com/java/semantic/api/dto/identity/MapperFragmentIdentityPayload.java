package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** mapper fragment 實體證據的共享 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MapperFragmentIdentityPayload(
        @MonitoringField(MonitoringMode.VALUE)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String namespace,
        @MonitoringField(MonitoringMode.VALUE)
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{javaISOControl}]+") String fragmentId,
        @MonitoringField(MonitoringMode.OMIT)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String resourcePath,
        @MonitoringField(MonitoringMode.VALUE) @PositiveOrZero int documentOrdinal,
        @MonitoringField(MonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "MAPPER_XML_ELEMENT") String representation) {

    public MapperFragmentIdentityPayload {
        namespace = Objects.requireNonNull(namespace, "namespace is required");
        fragmentId = Objects.requireNonNull(fragmentId, "fragmentId is required");
        resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
        representation = Objects.requireNonNull(representation, "representation is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown mapper fragment identity property");
    }
}
