package com.java.semantic.query.config;

import com.java.semantic.model.repository.RepositoryId;

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
}
