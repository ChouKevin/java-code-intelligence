package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
final class RepositorySourceAdapter implements RepositorySourcePort {
    private final RepositoryRuntimeRegistry registry;
    private final GitRepositoryPort git;
    private final FixtureRevisionResolver fixtures;

    RepositorySourceAdapter(RepositoryRuntimeRegistry registry,
                            GitRepositoryPort git, FixtureRevisionResolver fixtures) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.git = Objects.requireNonNull(git, "git is required");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures is required");
    }

    @Override
    public RepositoryRevision ensure(RepositoryId repositoryId) {
        return resolve(repositoryId, Optional.empty());
    }

    @Override
    public RepositoryRevision sync(RepositoryId repositoryId, Optional<String> branch) {
        return resolve(repositoryId, branch);
    }

    @Override
    public RepositoryRevision checkout(RepositoryId repositoryId, String revision) {
        if (!revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("checkout revision must be a lowercase SHA-1");
        }
        RepositoryRuntime runtime = registry.get(repositoryId);
        if (runtime.mode() == RepositoryMode.LOCAL_FIXTURE) {
            throw new IllegalArgumentException("local fixtures do not support checkout by revision");
        }
        return git.resolveRemoteRef(runtime.remoteUrl(), revision);
    }

    private RepositoryRevision resolve(RepositoryId repositoryId, Optional<String> branch) {
        RepositoryRuntime runtime = registry.get(repositoryId);
        if (runtime.mode() == RepositoryMode.LOCAL_FIXTURE) {
            return fixtures.resolve(runtime.workingTree());
        }
        String target = branch.filter(org.springframework.util.StringUtils::hasText).orElse(runtime.defaultBranch());
        return git.resolveRemoteRef(runtime.remoteUrl(), target);
    }

}
