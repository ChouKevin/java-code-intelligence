package com.java.semantic.repository.port;

import com.java.semantic.model.repository.RepositoryRevision;

import java.nio.file.Path;

/** Git 操作的唯一出口,語意層與控制器都不得直接使用 JGit */
public interface GitRepositoryPort {

    boolean isCloned(Path workingTree);

    RepositoryRevision clone(Path workingTree, String remoteUrl);

    void fetch(Path workingTree);

    void checkoutDetached(Path workingTree, RepositoryRevision revision);

    RepositoryRevision currentRevision(Path workingTree);

    RepositoryRevision resolveRemoteRef(String remoteUrl, String ref);
}
