package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.identity.SourceSymbolContextPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;
import java.util.Optional;

/** source-symbol resolve endpoint 的封閉請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ResolveSourceSymbolRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceSymbolContextPayload context,
        @MonitoringField(MonitoringMode.VALUE) @NotBlank
        @Pattern(regexp = "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]"
                + "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*") String symbol,
        @MonitoringField(MonitoringMode.NESTED) @Valid Optional<PositionPayload> position) {

    public ResolveSourceSymbolRequest {
        position = Objects.requireNonNull(position, "position is required");
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
