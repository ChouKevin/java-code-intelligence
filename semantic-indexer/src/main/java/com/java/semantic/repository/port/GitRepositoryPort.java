package com.java.semantic.repository.port;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCommit;
import com.java.semantic.model.git.GitPreparedComparison;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Git 操作的唯一出口,語意層與控制器都不得直接使用 JGit */
public interface GitRepositoryPort {

    boolean isCloned(Path workingTree);

    RepositoryRevision clone(Path workingTree, String remoteUrl);

    void fetch(Path workingTree);

    /** Verifies immutable comparison endpoints are still reachable from fetched trusted remote heads. */
    void verifyComparisonEndpoints(Path workingTree, RepositoryRevision previous, RepositoryRevision current);

    void checkoutDetached(Path workingTree, RepositoryRevision revision);

    RepositoryRevision currentRevision(Path workingTree);

    RepositoryRevision resolveRemoteRef(String remoteUrl, String ref);

    List<GitBranch> fetchRemoteBranches(Path workingTree);

    void streamReachableHistory(Path workingTree, RepositoryRevision revision, Consumer<GitCommit> consumer);

    GitPreparedComparison prepareComparison(Path workingTree, RepositoryRevision previous, RepositoryRevision current);
}
