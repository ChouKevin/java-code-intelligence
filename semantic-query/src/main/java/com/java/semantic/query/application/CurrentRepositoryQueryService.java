package com.java.semantic.query.application;

import com.java.semantic.model.query.SelectedGeneration;

import java.util.List;
import java.util.Objects;

public final class CurrentRepositoryQueryService {
    private final CurrentGenerationSelector selector;

    public CurrentRepositoryQueryService(CurrentGenerationSelector selector) {
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
    }

    public List<SelectedGeneration> listRepositories() {
        return selector.listCurrentRepositories();
    }

    public SelectedGeneration getRepository(String repositoryId) {
        return selector.currentRepository(repositoryId);
    }
}
