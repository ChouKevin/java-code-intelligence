package com.java.semantic.query.config;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import java.util.Objects;

/** Query-side authorization policy. Repository visibility is checked before any pointer read. */
public final class ConfiguredReadPolicy {

    private final ReadPolicyProperties properties;

    public ConfiguredReadPolicy(ReadPolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "read policy properties are required");
    }

    public boolean isRepositoryVisible(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        return !properties.forbiddenRepositories().contains(repositoryId.value());
    }

    public boolean isSourceVisible(RepositoryId repositoryId, SourceTypeIdentity sourceType) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        return !forbiddenPackage(repositoryId, identity) && !forbiddenClass(repositoryId, identity);
    }

    public boolean isCodeFactVisible(RepositoryId repositoryId, CodeFactIdentity codeFact) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        if (identity.canonicalIdentity() instanceof MethodTarget method) {
            return isSourceVisible(repositoryId, method.sourceType()) && !forbiddenMethod(repositoryId, method);
        }
        if (identity.canonicalIdentity() instanceof SourceTypeIdentity sourceType) {
            return isSourceVisible(repositoryId, sourceType);
        }
        if (identity.canonicalIdentity() instanceof MemberIdentity member) {
            return isSourceVisible(repositoryId, member.owner());
        }
        return true;
    }

    private boolean forbiddenPackage(RepositoryId repositoryId, SourceTypeIdentity identity) {
        return properties.forbiddenPackages().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && (identity.javaType().packageName().equals(rule.packagePrefix())
                || identity.javaType().packageName().startsWith(rule.packagePrefix() + ".")));
    }

    private boolean forbiddenClass(RepositoryId repositoryId, SourceTypeIdentity identity) {
        return properties.forbiddenClasses().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && rule.packageName().equals(identity.javaType().packageName()) && rule.className().equals(identity.javaType().className()));
    }

    private boolean forbiddenMethod(RepositoryId repositoryId, MethodTarget method) {
        return properties.forbiddenMethods().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && rule.packageName().equals(method.packageName()) && rule.className().equals(method.className())
                && rule.methodName().equals(method.methodName()) && rule.parameterTypes().equals(method.parameterTypes()));
    }
}
