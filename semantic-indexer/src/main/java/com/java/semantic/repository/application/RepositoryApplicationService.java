package com.java.semantic.repository.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** 儲存庫生命週期的應用服務邊界 */
public interface RepositoryApplicationService {

    RepositoryStatus ensure(RepositoryId repositoryId);

    RepositoryStatus sync(RepositoryId repositoryId, Optional<String> branch);

    RepositoryStatus checkout(RepositoryId repositoryId, String revision);

    RepositoryStatus status(RepositoryId repositoryId);

    List<RepositoryStatus> list();

    <T> T withSnapshot(
            RepositoryId repositoryId,
            Optional<RepositoryRevision> expectedRevision,
            Function<RepositorySnapshot, T> operation);
}
