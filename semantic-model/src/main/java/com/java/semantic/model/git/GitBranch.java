package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record GitBranch(String name, RepositoryRevision head) {
    public GitBranch {
        name = ModelValidation.requiredText(name, "git branch name");
        head = Objects.requireNonNull(head, "git branch head is required");
    }
}
