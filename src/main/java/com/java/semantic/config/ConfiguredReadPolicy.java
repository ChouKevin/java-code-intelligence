package com.java.semantic.config;

import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.identity.PolicyIdentity;

import java.util.Objects;

public final class ConfiguredReadPolicy implements ReadPolicy {

    private final ReadPolicyProperties properties;

    public ConfiguredReadPolicy(ReadPolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public EvidenceVisibility visibilityOfRepository(String repositoryId) {
        return properties.forbiddenRepositories().contains(repositoryId)
                ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                : EvidenceVisibility.READABLE;
    }

    @Override
    public EvidenceVisibility visibilityOf(MethodId methodId) {
        Objects.requireNonNull(methodId, "methodId is required");
        boolean forbidden = isRepositoryForbidden(methodId)
                || isPackageForbidden(methodId)
                || isClassForbidden(methodId)
                || isMethodForbidden(methodId);
        return forbidden ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN : EvidenceVisibility.READABLE;
    }

    @Override
    public EvidenceVisibility visibilityOf(TypeId typeId) {
        Objects.requireNonNull(typeId, "typeId is required");
        boolean forbidden = properties.forbiddenRepositories().contains(typeId.repoId())
                || properties.forbiddenPackages().stream()
                        .anyMatch(rule -> Objects.equals(rule.repoId(), typeId.repoId())
                                && matchesSegment(rule.packagePrefix(), typeId.packageName()))
                || properties.forbiddenClasses().stream()
                        .anyMatch(rule -> Objects.equals(rule.repoId(), typeId.repoId())
                                && Objects.equals(rule.packageName(), typeId.packageName())
                                && Objects.equals(PolicyIdentity.className(
                                        rule.packageName(), rule.className()), typeId.className()));
        return forbidden ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN : EvidenceVisibility.READABLE;
    }

    private boolean isRepositoryForbidden(MethodId methodId) {
        return properties.forbiddenRepositories().contains(methodId.repoId());
    }

    private boolean isPackageForbidden(MethodId methodId) {
        return properties.forbiddenPackages().stream()
                .anyMatch(rule -> Objects.equals(rule.repoId(), methodId.repoId())
                        && matchesSegment(rule.packagePrefix(), methodId.packageName()));
    }

    private boolean isClassForbidden(MethodId methodId) {
        return properties.forbiddenClasses().stream()
                .anyMatch(rule -> Objects.equals(rule.repoId(), methodId.repoId())
                        && Objects.equals(rule.packageName(), methodId.packageName())
                        && Objects.equals(PolicyIdentity.className(
                                rule.packageName(), rule.className()), methodId.className()));
    }

    private boolean isMethodForbidden(MethodId methodId) {
        return properties.forbiddenMethods().stream()
                .anyMatch(rule -> Objects.equals(rule.repoId(), methodId.repoId())
                        && Objects.equals(rule.packageName(), methodId.packageName())
                        && Objects.equals(PolicyIdentity.className(rule.packageName(), rule.className()), methodId.className())
                        && Objects.equals(rule.methodName(), methodId.methodName())
                        && Objects.equals(PolicyIdentity.parameterTypes(rule.parameterTypes()), methodId.parameterTypes()));
    }

    private boolean matchesSegment(String prefix, String candidate) {
        return Objects.equals(prefix, candidate) || candidate.startsWith(prefix + ".");
    }
}
