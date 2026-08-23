package com.java.semantic.repository.port;

import com.java.semantic.repository.domain.RepositoryId;

/** Git 變更前的失效通知擴充點 */
@FunctionalInterface
public interface RepositoryMutationListener {

    void beforeMutation(RepositoryId repositoryId);
}
