package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
final class GitRevisionResolver implements RepositoryRevisionResolver {
    private final RepositoryRuntimeRegistry registry;
    private final GitRepositoryPort git;

    GitRevisionResolver(RepositoryRuntimeRegistry registry, GitRepositoryPort git) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.git = Objects.requireNonNull(git, "git is required");
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
        RepositoryRevision exactRevision = RepositoryRevision.ofSha(revision);
        RepositoryRuntime runtime = registry.get(repositoryId);
        return git.resolveRemoteRef(runtime.remoteUrl(), exactRevision.value());
    }

    private RepositoryRevision resolve(RepositoryId repositoryId, Optional<String> branch) {
        RepositoryRuntime runtime = registry.get(repositoryId);
        String target = branch.filter(org.springframework.util.StringUtils::hasText).orElse(runtime.defaultBranch());
        return git.resolveRemoteRef(runtime.remoteUrl(), target);
    }

}
