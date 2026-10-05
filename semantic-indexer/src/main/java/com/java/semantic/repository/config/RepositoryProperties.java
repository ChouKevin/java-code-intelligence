package com.java.semantic.repository.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Private Git and filesystem roots; never returned through source transports. */
@ConfigurationProperties(prefix = "semantic")
public class RepositoryProperties {
    private String sourceAdminRoot = "/data/source-admin";
    private String sourcePublishedRoot = "/data/source-published";
    private String gitUsername = "";
    private String gitToken = "";
    private Map<String, RepositoryConfig> repositories = new LinkedHashMap<>();
    private final SourceRetentionConfig sourceRetention = new SourceRetentionConfig();

    public String getSourceAdminRoot() { return sourceAdminRoot; }
    public void setSourceAdminRoot(String root) { sourceAdminRoot = Objects.requireNonNull(root); }
    public String getSourcePublishedRoot() { return sourcePublishedRoot; }
    public void setSourcePublishedRoot(String root) { sourcePublishedRoot = Objects.requireNonNull(root); }
    public String getGitUsername() { return gitUsername; }
    public void setGitUsername(String name) { gitUsername = Objects.requireNonNull(name); }
    public String getGitToken() { return gitToken; }
    public void setGitToken(String token) { gitToken = Objects.requireNonNull(token); }
    public Map<String, RepositoryConfig> getRepositories() { return repositories; }
    public void setRepositories(Map<String, RepositoryConfig> configured) {
        repositories = Objects.requireNonNull(configured);
    }

    public SourceRetentionConfig getSourceRetention() { return sourceRetention; }

    public static final class SourceRetentionConfig {
        private boolean enabled = true;
        private Duration interval = Duration.ofHours(24);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) {
            Objects.requireNonNull(interval, "retention interval");
            if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("retention interval must be positive");
            this.interval = interval;
        }
    }

    @Override
    public String toString() {
        return "RepositoryProperties[repositories=" + repositories.keySet() + "]";
    }

    public static class RepositoryConfig {
        private String displayName = "";
        private String url = "";
        private String defaultBranch = "main";
        private String projectGuidePath = "";

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String name) { displayName = Objects.requireNonNull(name); }
        public String getUrl() { return url; }
        public void setUrl(String value) { url = Objects.requireNonNull(value); }
        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String branch) { defaultBranch = Objects.requireNonNull(branch); }
        public String getProjectGuidePath() { return projectGuidePath; }
        public void setProjectGuidePath(String path) { projectGuidePath = Objects.requireNonNull(path); }
    }
}
