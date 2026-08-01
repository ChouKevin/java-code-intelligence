package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** canonical method name 與 optional overload signature selector */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceSymbolMethodContextRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Size(max = 255)
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*") String name,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<List<@NotBlank String>> parameterTypes) {

    public SourceSymbolMethodContextRequest {
        parameterTypes = Objects.requireNonNull(parameterTypes, "parameterTypes is required").map(List::copyOf);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown method context property");
    }
}
