package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Objects;

/** Request for a bounded, revision-bound outgoing call-graph fragment. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AnalyzeOutgoingCallGraphRequest(
        @NotBlank String repoId,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
        @Min(1) @Max(2) Integer depth,
        @NotNull @Valid MethodTargetRequest target) {

    public AnalyzeOutgoingCallGraphRequest {
        depth = Objects.requireNonNullElse(depth, 2);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
