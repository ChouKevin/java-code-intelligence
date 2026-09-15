package com.java.semantic.query.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

/** Explicit whole-repository visibility for immutable Git evidence. */
@Validated
@ConfigurationProperties(prefix = "semantic.query.git-evidence")
public record GitEvidenceProperties(@DefaultValue List<@NotBlank String> allowedRepositories) {
    @ConstructorBinding
    public GitEvidenceProperties {
        allowedRepositories = List.copyOf(Objects.requireNonNull(allowedRepositories, "git evidence allowed repositories are required"));
    }
}
