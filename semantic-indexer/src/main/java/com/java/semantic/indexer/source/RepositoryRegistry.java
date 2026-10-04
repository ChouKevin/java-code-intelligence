package com.java.semantic.indexer.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.repository.config.RepositoryProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Private approved Git targets; only sanitized descriptors may leave this boundary. */
@Component
public final class RepositoryRegistry {
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private final Map<String, RepositoryProperties.RepositoryConfig> configured;

    public RepositoryRegistry(RepositoryProperties properties) {
        configured = Map.copyOf(properties.getRepositories());
        for (Map.Entry<String, RepositoryProperties.RepositoryConfig> entry : configured.entrySet()) {
            new RepositoryId(entry.getKey());
            RepositoryProperties.RepositoryConfig repository = entry.getValue();
            if (repository.getUrl().isBlank() || !BRANCH.matcher(repository.getDefaultBranch()).matches()
                    || repository.getDefaultBranch().contains("..") || repository.getDefaultBranch().contains("//")
                    || repository.getDefaultBranch().endsWith("." ) || repository.getDefaultBranch().endsWith("/")) {
                throw new IllegalArgumentException("repository Git URL and default branch must be configured safely");
            }
            if (repository.getDisplayName().isBlank()) {
                throw new IllegalArgumentException("repository display name must be configured");
            }
        }
    }

    public RepositoryProperties.RepositoryConfig require(RepositoryId id) {
        RepositoryProperties.RepositoryConfig config = configured.get(id.value());
        if (java.util.Objects.isNull(config)) {
            throw new RepositoryNotConfiguredException();
        }
        return config;
    }

    public List<SourceRepositoryDescriptor> descriptors() {
        List<SourceRepositoryDescriptor> results = new ArrayList<>();
        for (String id : configured.keySet()) {
            RepositoryProperties.RepositoryConfig config = configured.get(id);
            Optional<String> guide = Optional.of(config.getProjectGuidePath()).filter(path -> !path.isBlank())
                    .filter(path -> path.endsWith(".md") && safePath(path));
            results.add(new SourceRepositoryDescriptor(id, config.getDisplayName(), config.getDefaultBranch(), guide));
        }
        results.sort(Comparator.comparing(SourceRepositoryDescriptor::repositoryId));
        return List.copyOf(results);
    }

    private static boolean safePath(String path) {
        try {
            SourcePathPolicy.requireFile(path);
            return !SourcePathPolicy.isExcluded(path);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static final class RepositoryNotConfiguredException extends RuntimeException {
        public RepositoryNotConfiguredException() {
            super("repository is not configured");
        }
    }
}
