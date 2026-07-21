package com.java.semantic.repository.port;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;

/** 儲存庫快照發布前後的通用生命週期 */
public interface RepositorySnapshotPublicationListener {

    void beforePublication(RepositoryId repositoryId);

    void afterPublication(RepositorySnapshot snapshot);
}
