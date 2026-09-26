package com.java.semantic.indexer.repository;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.Lock;

/** Materializes the revision admitted to an index job without re-resolving a moving branch. */
@Component
public final class ExactRepositoryCheckout implements IndexBuildService.CheckoutResolver {
    private final RepositoryRuntimeRegistry registry;
    private final GitRepositoryPort git;
    private final RepositoryMutationListener mutationListener;

    public ExactRepositoryCheckout(RepositoryRuntimeRegistry registry, GitRepositoryPort git,
                                   RepositoryMutationListener mutationListener) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.git = Objects.requireNonNull(git, "git is required");
        this.mutationListener = Objects.requireNonNull(mutationListener, "mutation listener is required");
    }

    @Override
    public IndexBuildService.CheckedOutRepository checkout(IndexJob job) {
        Objects.requireNonNull(job, "job is required");
        RepositoryRuntime runtime = registry.get(job.repositoryId());
        Lock writeLock = runtime.lock().writeLock();
        writeLock.lock();
        try {
            mutationListener.beforeMutation(job.repositoryId());
            Path root = runtime.workingTree().toAbsolutePath().normalize();
            try {
                runtime.managedCheckout().validate(root);
            } catch (IOException exception) {
                throw new IllegalStateException("managed checkout boundary is invalid", exception);
            }
            if (git.isCloned(root)) {
                git.fetch(root);
            } else {
                git.clone(root, runtime.remoteUrl());
            }
            RepositoryRevision requested = job.target().orElseThrow(() -> new IllegalArgumentException("build requires a target")).revision();
            git.checkoutDetached(root, requested);
            RepositoryRevision actualRevision = git.currentRevision(root);
            if (!requested.equals(actualRevision)) {
                throw new RepositoryMutationException("checked out revision differs from admitted revision");
            }
            runtime.publish(actualRevision);
            return new IndexBuildService.CheckedOutRepository(root, actualRevision, runtime.managedCheckout());
        } finally {
            writeLock.unlock();
        }
    }
}
