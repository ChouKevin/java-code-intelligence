package com.java.semantic.api;

import com.java.semantic.api.dto.RepositoryStatusResponse;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryStatus;
import org.springframework.stereotype.Component;

/** 將 domain status 投影成安全的 HTTP DTO */
@Component
public class RepositoryStatusMapper {

    public RepositoryStatusResponse toResponse(RepositoryStatus status) {
        return new RepositoryStatusResponse(
                status.repositoryId().value(),
                status.mode().name(),
                status.displayName(),
                status.currentBranch(),
                status.currentRevision().map(RepositoryRevision::value),
                status.cloned());
    }
}
