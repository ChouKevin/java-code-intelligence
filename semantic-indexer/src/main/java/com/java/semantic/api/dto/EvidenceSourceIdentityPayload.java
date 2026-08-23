package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.dto.identity.MapperFragmentIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementIdentityPayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;
import java.util.Optional;

/** evidence endpoint 唯一接受的三種封閉 typed identity */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvidenceSourceIdentityPayload(
        @MonitoringField(MonitoringMode.VALUE)
        @NotBlank @Pattern(regexp = "ANNOTATION_SQL|MAPPER_STATEMENT|MAPPER_FRAGMENT") String kind,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.NESTED) Optional<@Valid MapperStatementIdentityPayload> statementIdentity,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.NESTED) Optional<@Valid MapperFragmentIdentityPayload> fragmentIdentity) {

    public EvidenceSourceIdentityPayload {
        kind = Objects.requireNonNull(kind, "kind is required");
        statementIdentity = Optional.ofNullable(statementIdentity).orElse(Optional.empty());
        fragmentIdentity = Optional.ofNullable(fragmentIdentity).orElse(Optional.empty());
        if (!kind.equals("ANNOTATION_SQL") && !kind.equals("MAPPER_STATEMENT") && !kind.equals("MAPPER_FRAGMENT")) {
            throw new IllegalArgumentException("unsupported evidence identity kind");
        }
        boolean statementKind = kind.equals("ANNOTATION_SQL") || kind.equals("MAPPER_STATEMENT");
        if (statementKind != statementIdentity.isPresent() || kind.equals("MAPPER_FRAGMENT") != fragmentIdentity.isPresent()) {
            throw new IllegalArgumentException("evidence identity does not match kind");
        }
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown evidence identity property");
    }
}
