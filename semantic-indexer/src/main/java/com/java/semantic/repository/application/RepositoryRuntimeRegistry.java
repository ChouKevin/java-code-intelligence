package com.java.semantic.repository.application;
import com.java.semantic.model.repository.InvalidRepositoryIdException;

import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.repository.InvalidRepositoryIdException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRuntime;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 將已驗證設定解析成每個 repoId 唯一的 runtime */
@Component
public class RepositoryRuntimeRegistry {

    private final Map<RepositoryId, RepositoryRuntime> runtimes;

    public RepositoryRuntimeRegistry(RepositoryProperties properties) {
        Objects.requireNonNull(properties, "properties is required");
        Path dataRoot = Path.of(properties.getDataRoot()).toAbsolutePath().normalize();
        Map<RepositoryId, RepositoryRuntime> configured = new LinkedHashMap<>();
        for (Map.Entry<String, RepositoryProperties.RepositoryConfig> entry
                : properties.getRepositories().entrySet()) {
            RepositoryId repositoryId = RepositoryId.of(entry.getKey());
            RepositoryProperties.RepositoryConfig config = entry.getValue();
            configured.put(repositoryId, createRuntime(repositoryId, config, dataRoot));
        }
        runtimes = Map.copyOf(configured);
    }

    public RepositoryRuntime get(RepositoryId repositoryId) {
        return Optional.ofNullable(runtimes.get(repositoryId))
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    }

    public List<RepositoryRuntime> all() {
        return new ArrayList<>(runtimes.values());
    }

    private RepositoryRuntime createRuntime(
            RepositoryId repositoryId,
            RepositoryProperties.RepositoryConfig config,
            Path dataRoot) {
        RepositoryMode mode = Objects.requireNonNull(config.getMode(), "repository mode is required");
        String displayName = StringUtils.hasText(config.getDisplayName())
                ? config.getDisplayName()
                : repositoryId.value();
        String defaultBranch = StringUtils.hasText(config.getDefaultBranch())
                ? config.getDefaultBranch()
                : "main";
        Path workingTree = resolveWorkingTree(repositoryId, mode, config, dataRoot);
        return new RepositoryRuntime(
                repositoryId,
                mode,
                displayName,
                workingTree,
                config.getUrl(),
                defaultBranch);
    }

    private Path resolveWorkingTree(
            RepositoryId repositoryId,
            RepositoryMode mode,
            RepositoryProperties.RepositoryConfig config,
            Path dataRoot) {
        if (RepositoryMode.LOCAL_FIXTURE == mode) {
            Assert.hasText(config.getPath(), "fixture path is required");
            return Path.of(config.getPath()).toAbsolutePath().normalize();
        }
        Path workingTree = dataRoot.resolve(repositoryId.value()).normalize();
        if (!workingTree.startsWith(dataRoot)) {
            throw new InvalidRepositoryIdException(repositoryId.value());
        }
        return workingTree;
    }
}
