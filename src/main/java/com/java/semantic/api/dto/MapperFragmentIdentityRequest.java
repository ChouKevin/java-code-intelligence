package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.identity.RepositoryRelativeSource;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** mapper fragment 的封閉 repository-relative exact identity 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MapperFragmentIdentityRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+")
        String namespace,
        @ApiMonitoringField(ApiMonitoringMode.VALUE)
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{javaISOControl}]+")
        String fragmentId,
        @ApiMonitoringField(ApiMonitoringMode.OMIT)
        @NotBlank @Size(max = 1024) @Pattern(regexp = "[^\\p{javaISOControl}]+")
        String resourcePath,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @PositiveOrZero int documentOrdinal,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotNull MapperEvidenceRepresentation representation) {

    public MapperFragmentIdentityRequest {
        resourcePath = RepositoryRelativeSource.requireValid(resourcePath);
        if (representation != MapperEvidenceRepresentation.MAPPER_XML_ELEMENT) {
            throw new IllegalArgumentException("mapper fragment representation must be XML");
        }
    }

    /** 轉換為 application service 使用的 exact fragment identity */
    public MapperFragmentIdentity toDomain() {
        return new MapperFragmentIdentity(
                namespace,
                fragmentId,
                resourcePath,
                documentOrdinal,
                representation);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown fragment identity property");
    }
}
