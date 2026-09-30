package com.java.semantic.repository.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 儲存庫設定,含 git 認證,永不外流至 HTTP API */
@ConfigurationProperties(prefix = "semantic")
public class RepositoryProperties {

    private String dataRoot = "/data/repos";
    private Duration repositoryLockTimeout = Duration.ofSeconds(5);
    private String gitUsername = "";
    private String gitToken = "";
    private long gitEvidenceFileTextBytes = 2L * 1024L * 1024L;
    private long gitEvidenceSnapshotTextBytes = 256L * 1024L * 1024L;
    private Map<String, RepositoryConfig> repositories = new LinkedHashMap<>();

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }

    public Duration getRepositoryLockTimeout() {
        return repositoryLockTimeout;
    }

    public void setRepositoryLockTimeout(Duration repositoryLockTimeout) {
        this.repositoryLockTimeout = repositoryLockTimeout;
    }

    public String getGitUsername() {
        return gitUsername;
    }

    public void setGitUsername(String gitUsername) {
        this.gitUsername = gitUsername;
    }

    public String getGitToken() {
        return gitToken;
    }

    public void setGitToken(String gitToken) {
        this.gitToken = gitToken;
    }

    public long getGitEvidenceFileTextBytes() {
        return gitEvidenceFileTextBytes;
    }

    public void setGitEvidenceFileTextBytes(long gitEvidenceFileTextBytes) {
        if (gitEvidenceFileTextBytes <= 0L) {
            throw new IllegalArgumentException("git evidence file text bytes must be positive");
        }
        this.gitEvidenceFileTextBytes = gitEvidenceFileTextBytes;
    }

    public long getGitEvidenceSnapshotTextBytes() {
        return gitEvidenceSnapshotTextBytes;
    }

    public void setGitEvidenceSnapshotTextBytes(long gitEvidenceSnapshotTextBytes) {
        if (gitEvidenceSnapshotTextBytes <= 0L) {
            throw new IllegalArgumentException("git evidence snapshot text bytes must be positive");
        }
        this.gitEvidenceSnapshotTextBytes = gitEvidenceSnapshotTextBytes;
    }

    public Map<String, RepositoryConfig> getRepositories() {
        return repositories;
    }

    public void setRepositories(Map<String, RepositoryConfig> repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories is required");
    }

    /** 不輸出 token */
    @Override
    public String toString() {
        return "RepositoryProperties(gitUsername=" + gitUsername
                + ", gitToken=" + (StringUtils.hasText(gitToken) ? "<set>" : "<unset>")
                + ", repositories=" + repositories.keySet() + ")";
    }

    public static class RepositoryConfig {
        private String displayName = "";
        private String url = "";
        private String defaultBranch = "main";
        private String projectGuidePath = "";


        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDefaultBranch() {
            return defaultBranch;
        }

        public void setDefaultBranch(String defaultBranch) {
            this.defaultBranch = defaultBranch;
        }

        public String getProjectGuidePath() {
            return projectGuidePath;
        }

        public void setProjectGuidePath(String projectGuidePath) {
            if (Objects.nonNull(projectGuidePath) && !projectGuidePath.isEmpty()
                    && (!com.java.semantic.model.source.SourceEvidencePolicy.validPath(projectGuidePath)
                            || !projectGuidePath.endsWith(".md"))) {
                throw new IllegalArgumentException("project guide must be a relative Markdown path");
            }
            this.projectGuidePath = Objects.requireNonNull(projectGuidePath, "guide path is required");
        }

    }
}
