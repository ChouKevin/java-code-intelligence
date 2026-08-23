package com.java.semantic.repository.application;

import com.java.semantic.repository.domain.RepositoryId;

/** 儲存庫尚未準備可讀快照 */
public class RepositoryNotReadyException extends RuntimeException {

    public RepositoryNotReadyException(RepositoryId repositoryId) {
        super("repository is not ready: " + repositoryId.value());
    }
}
