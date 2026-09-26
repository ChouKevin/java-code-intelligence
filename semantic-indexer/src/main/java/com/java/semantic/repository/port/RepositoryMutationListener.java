package com.java.semantic.repository.port;

import com.java.semantic.model.repository.RepositoryId;

/** Git 變更前的安全閘門:所有共用分析 UID 的受管子程序都必須已確認退出。 */
@FunctionalInterface
public interface RepositoryMutationListener {

    void beforeMutation(RepositoryId repositoryId);
}
