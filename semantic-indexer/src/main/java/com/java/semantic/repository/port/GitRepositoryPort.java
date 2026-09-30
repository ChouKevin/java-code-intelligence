package com.java.semantic.repository.port;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCommit;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.TrackedSourceInventory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Git 操作的唯一出口,語意層與控制器都不得直接使用 JGit */
public interface GitRepositoryPort {

    boolean isCloned(Path workingTree);

    RepositoryRevision clone(Path workingTree, String remoteUrl);

    void fetch(Path workingTree, String remoteUrl);

    /** Verifies immutable comparison endpoints are still reachable from fetched trusted remote heads. */
    void verifyComparisonEndpoints(Path workingTree, RepositoryRevision previous, RepositoryRevision current);
    /** Verifies requested commits after fetch and fixes the first parent or empty tree once. */
    ResolvedReviewEndpoints resolveReviewEndpoints(Path workingTree, ReviewSelection selection);


    void checkoutDetached(Path workingTree, RepositoryRevision revision);

    RepositoryRevision currentRevision(Path workingTree);

    RepositoryRevision resolveRemoteRef(String remoteUrl, String ref);

    List<GitBranch> fetchRemoteBranches(Path workingTree, String remoteUrl);

    void streamReachableHistory(Path workingTree, RepositoryRevision revision, Consumer<GitCommit> consumer);

    TrackedSourceInventory prepareSnapshot(Path workingTree, RepositoryRevision revision, SourceEvidencePolicy policy);

    GitPreparedComparison prepareComparison(Path workingTree, Optional<RepositoryRevision> previous,
            RepositoryRevision current, SourceEvidencePolicy previousPolicy, SourceEvidencePolicy currentPolicy,
            Set<String> availableGuidesBefore, Set<String> availableGuidesAfter);
}
