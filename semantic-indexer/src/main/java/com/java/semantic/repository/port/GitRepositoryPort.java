package com.java.semantic.repository.port;

import com.java.semantic.model.repository.RepositoryRevision;

import java.nio.file.Path;

/** Git 操作的唯一出口,語意層與控制器都不得直接使用 JGit */
public interface GitRepositoryPort {

    boolean isCloned(Path workingTree);

    RepositoryRevision clone(Path workingTree, String url, String branch);

    RepositoryRevision fetchAndReset(Path workingTree, String branch);

    RepositoryRevision checkout(Path workingTree, String revision);

    RepositoryRevision currentRevision(Path workingTree);

    String currentBranch(Path workingTree);
}
