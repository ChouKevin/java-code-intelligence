package com.java.semantic.query.application;

import com.java.semantic.model.query.CurrentGeneration;

import java.util.List;
import java.util.Objects;

public final class CurrentRepositoryQueryService {
    private final CurrentGenerationSelector selector;

    public CurrentRepositoryQueryService(CurrentGenerationSelector selector) {
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
    }

    public List<CurrentGeneration> listRepositories() {
        return selector.listCurrentRepositories();
    }

    public CurrentGeneration getRepository(String repositoryId) {
        return selector.currentRepository(repositoryId);
    }
}
