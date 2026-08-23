package com.java.semantic.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "semantic.analysis.read-policy")
public record ReadPolicyProperties(
        @DefaultValue List<@NotBlank String> forbiddenRepositories,
        @DefaultValue List<@Valid PackageRule> forbiddenPackages,
        @DefaultValue List<@Valid ClassRule> forbiddenClasses,
        @DefaultValue List<@Valid MethodRule> forbiddenMethods) {

    public ReadPolicyProperties {
        forbiddenRepositories = List.copyOf(Objects.requireNonNull(
                forbiddenRepositories, "forbiddenRepositories is required"));
        forbiddenPackages = List.copyOf(Objects.requireNonNull(
                forbiddenPackages, "forbiddenPackages is required"));
        forbiddenClasses = List.copyOf(Objects.requireNonNull(
                forbiddenClasses, "forbiddenClasses is required"));
        forbiddenMethods = List.copyOf(Objects.requireNonNull(
                forbiddenMethods, "forbiddenMethods is required"));
    }

    public record PackageRule(
            @NotBlank String repoId,
            @NotBlank String packagePrefix) {
    }

    public record ClassRule(
            @NotBlank String repoId,
            @NotBlank String packageName,
            @NotBlank String className) {
    }

    public record MethodRule(
            @NotBlank String repoId,
            @NotBlank String packageName,
            @NotBlank String className,
            @NotBlank String methodName,
            @DefaultValue List<@NotBlank String> parameterTypes) {

        public MethodRule {
            parameterTypes = List.copyOf(Objects.requireNonNull(
                    parameterTypes, "parameterTypes is required"));
        }
    }
}
