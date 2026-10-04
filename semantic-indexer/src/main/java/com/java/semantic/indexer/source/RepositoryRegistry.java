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
    private final Map<String, String> origins;
    private final Map<String, String> endpoints;
    private final ApprovedOriginBinding binding;

    public RepositoryRegistry(RepositoryProperties properties) {
        configured = Map.copyOf(properties.getRepositories());
        binding = new ApprovedOriginBinding(properties);
        java.util.Map<String, String> approved = new java.util.HashMap<>();
        java.util.Map<String, String> approvedEndpoints = new java.util.HashMap<>();
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
            approved.put(entry.getKey(), ApprovedOriginBinding.fingerprint(repository.getUrl()));
            approvedEndpoints.put(entry.getKey(), repository.getUrl());
        }
        origins = Map.copyOf(approved);
        endpoints = Map.copyOf(approvedEndpoints);
    }

    public void bindAll() {
        for (String id : configured.keySet()) {
            ensureEndpoint(id);
        }
        for (String id : configured.keySet()) {
            binding.preflight(new RepositoryId(id), origins.get(id));
        }
        for (String id : configured.keySet()) {
            binding.bind(new RepositoryId(id), origins.get(id));
        }
    }

    public String origin(RepositoryId id) {
        ensureEndpoint(id.value());
        binding.bind(id, origins.get(id.value()));
        return origins.get(id.value());
    }

    public void requireOrigin(RepositoryId id, String expected) {
        if (!origin(id).equals(expected)) {
            throw new IllegalStateException("approved repository origin changed since acceptance");
        }
    }

    public String endpoint(RepositoryId id) {
        require(id);
        return endpoints.get(id.value());
    }

    public RepositoryProperties.RepositoryConfig require(RepositoryId id) {
        RepositoryProperties.RepositoryConfig config = configured.get(id.value());
        if (java.util.Objects.isNull(config)) {
            throw new RepositoryNotConfiguredException();
        }
        ensureEndpoint(id.value());
        binding.bind(id, origins.get(id.value()));
        return config;
    }

    private void ensureEndpoint(String id) {
        RepositoryProperties.RepositoryConfig config = configured.get(id);
        if (java.util.Objects.isNull(config)) throw new RepositoryNotConfiguredException();
        if (!config.getUrl().equals(endpoints.get(id))) {
            throw new IllegalStateException("approved repository origin changed since startup");
        }
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
