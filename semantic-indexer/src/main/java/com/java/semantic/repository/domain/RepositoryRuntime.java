package com.java.semantic.repository.domain;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 單一儲存庫的工作樹、版本與公平讀寫鎖 */
public final class RepositoryRuntime {

    private final RepositoryId repositoryId;
    private final RepositoryMode mode;
    private final String displayName;
    private final Path workingTree;
    private final String remoteUrl;
    private final String defaultBranch;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private RepositorySnapshot snapshot;
    private String currentBranch = "";

    public RepositoryRuntime(
            RepositoryId repositoryId,
            RepositoryMode mode,
            String displayName,
            Path workingTree,
            String remoteUrl,
            String defaultBranch) {
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        this.mode = Objects.requireNonNull(mode, "mode is required");
        this.displayName = Objects.requireNonNull(displayName, "displayName is required");
        this.workingTree = Objects.requireNonNull(workingTree, "workingTree is required");
        this.remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl is required");
        this.defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch is required");
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }

    public RepositoryMode mode() {
        return mode;
    }

    public Path workingTree() {
        return workingTree;
    }

    public String remoteUrl() {
        return remoteUrl;
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    public ReentrantReadWriteLock lock() {
        return lock;
    }

    public Optional<RepositorySnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void publish(RepositoryRevision revision, String branch) {
        snapshot = new RepositorySnapshot(repositoryId, workingTree, revision);
        currentBranch = branch;
    }

    public RepositoryStatus status() {
        Optional<RepositorySnapshot> currentSnapshot = snapshot();
        Optional<String> branch = StringUtils.hasText(currentBranch)
                ? Optional.of(currentBranch)
                : Optional.empty();
        return new RepositoryStatus(
                repositoryId,
                mode,
                displayName,
                branch,
                currentSnapshot.map(RepositorySnapshot::revision),
                currentSnapshot.isPresent());
    }
}
