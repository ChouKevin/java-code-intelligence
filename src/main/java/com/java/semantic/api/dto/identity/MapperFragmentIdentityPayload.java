package com.java.semantic.api.dto.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/** mapper fragment 實體證據的共享 HTTP identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MapperFragmentIdentityPayload(
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String namespace,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{javaISOControl}]+") String fragmentId,
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+") String resourcePath,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @PositiveOrZero int documentOrdinal,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
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
