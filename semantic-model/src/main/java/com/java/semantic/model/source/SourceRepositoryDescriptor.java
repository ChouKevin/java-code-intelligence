package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;
import java.util.Optional;

public record SourceRepositoryDescriptor(String repositoryId, String displayName, String defaultBranch,
        Optional<String> projectGuidePath, Optional<RepositoryNavigationHint> navigationHint) {

    public SourceRepositoryDescriptor {
        repositoryId = new RepositoryId(repositoryId).value();
        displayName = ModelValidation.requiredText(displayName, "display name");
        defaultBranch = ModelValidation.requiredText(defaultBranch, "default branch");
        projectGuidePath = Objects.requireNonNull(projectGuidePath, "project guide path");
        projectGuidePath.ifPresent(SourcePathPolicy::requireFile);
        navigationHint = Objects.requireNonNull(navigationHint, "navigation hint");
    }
}
