package com.java.semantic.repository.application;

import com.java.semantic.repository.domain.RepositoryId;

/** repoId 未出現在服務設定 */
public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException(RepositoryId repositoryId) {
        super("repository is not configured: " + repositoryId.value());
    }
}
