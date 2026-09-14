package com.java.semantic.query.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "semantic.query.read-policy")
public record ReadPolicyProperties(
        @DefaultValue List<@NotBlank String> gitEvidenceAllowedRepositories,
        @DefaultValue List<@NotBlank String> forbiddenRepositories,
        @DefaultValue List<@Valid PackageRule> forbiddenPackages,
        @DefaultValue List<@Valid ClassRule> forbiddenClasses,
        @DefaultValue List<@Valid MethodRule> forbiddenMethods) {

    @ConstructorBinding
    public ReadPolicyProperties {
        gitEvidenceAllowedRepositories = List.copyOf(Objects.requireNonNull(gitEvidenceAllowedRepositories, "git evidence allowed repositories are required"));
        forbiddenRepositories = List.copyOf(Objects.requireNonNull(forbiddenRepositories, "forbidden repositories are required"));
        forbiddenPackages = List.copyOf(Objects.requireNonNull(forbiddenPackages, "forbidden packages are required"));
        forbiddenClasses = List.copyOf(Objects.requireNonNull(forbiddenClasses, "forbidden classes are required"));
        forbiddenMethods = List.copyOf(Objects.requireNonNull(forbiddenMethods, "forbidden methods are required"));
    }

    public ReadPolicyProperties(List<String> forbiddenRepositories, List<PackageRule> forbiddenPackages,
                                List<ClassRule> forbiddenClasses, List<MethodRule> forbiddenMethods) {
        this(List.of(), forbiddenRepositories, forbiddenPackages, forbiddenClasses, forbiddenMethods);
    }

    public record PackageRule(@NotBlank String repoId, @NotBlank String packagePrefix) { }
    public record ClassRule(@NotBlank String repoId, @NotBlank String packageName, @NotBlank String className) { }
    public record MethodRule(@NotBlank String repoId, @NotBlank String packageName, @NotBlank String className,
                             @NotBlank String methodName, @DefaultValue List<@NotBlank String> parameterTypes) {
        public MethodRule {
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameter types are required"));
        }
    }
}
