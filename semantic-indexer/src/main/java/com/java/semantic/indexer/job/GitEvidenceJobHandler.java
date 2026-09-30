package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Optional;

/** Runs Git evidence preparation inside the existing serialized dispatcher. */
@Component
public final class GitEvidenceJobHandler {
    private final RepositoryRuntimeRegistry repositories;
    private final GitRepositoryPort git;
    private final GitEvidencePublicationStore evidence;
    private final RepositoryMutationListener mutationListener;

    public GitEvidenceJobHandler(RepositoryRuntimeRegistry repositories, GitRepositoryPort git,
                                 GitEvidencePublicationStore evidence, RepositoryMutationListener mutationListener) {
        this.repositories = Objects.requireNonNull(repositories, "repositories are required");
        this.git = Objects.requireNonNull(git, "git repository port is required");
        this.evidence = Objects.requireNonNull(evidence, "git evidence store is required");
        this.mutationListener = Objects.requireNonNull(mutationListener, "mutation listener is required");
    }

    public void prepare(IndexJob job) {
        RepositoryRuntime runtime = repositories.get(job.repositoryId());
        runtime.lock().writeLock().lock();
        try {
            evidence.verifySchemaBeforeEvidence();
            try {
                mutationListener.beforeMutation(job.repositoryId());
                validateCheckout(runtime);
                if (job.operation() != IndexJobOperation.GIT_METADATA) {
                    throw new IllegalArgumentException("not a metadata refresh job");
                }
                metadata(job, runtime);
            } catch (RuntimeException exception) {
                evidence.fail(job);
                throw exception;
            }
        } finally {
            runtime.lock().writeLock().unlock();
        }
    }

    public ResolvedReviewEndpoints resolveReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW) {
            throw new IllegalArgumentException("review resolution requires a REVIEW job");
        }
        RepositoryRuntime runtime = repositories.get(requiredJob.repositoryId());
        runtime.lock().writeLock().lock();
        try {
            mutationListener.beforeMutation(requiredJob.repositoryId());
            validateCheckout(runtime);
            if (!git.isCloned(runtime.workingTree())) {
                git.clone(runtime.workingTree(), runtime.remoteUrl());
            }
            git.fetch(runtime.workingTree(), runtime.remoteUrl());
            return git.resolveReviewEndpoints(runtime.workingTree(), requiredJob.review().orElseThrow().selection());
        } finally {
            runtime.lock().writeLock().unlock();
        }
    }

    public void prepareReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW) {
            throw new IllegalArgumentException("review Git evidence requires a REVIEW job");
        }
        ReviewJobPayload payload = requiredJob.review().orElseThrow(() -> new IllegalArgumentException("review payload is required"));
        ResolvedReviewEndpoints endpoints = payload.resolvedEndpoints().orElseThrow();
        RepositoryRuntime runtime = repositories.get(requiredJob.repositoryId());
        runtime.lock().writeLock().lock();
        try {
            mutationListener.beforeMutation(requiredJob.repositoryId());
            validateCheckout(runtime);
            Optional<GitEvidencePublicationStore.PreparedSource> before = payload.before().map(evidence::preparedSource);
            GitEvidencePublicationStore.PreparedSource after = evidence.preparedSource(payload.after().orElseThrow());
            SourceEvidencePolicy beforePolicy = before.map(
                    GitEvidencePublicationStore.PreparedSource::policy).orElseGet(() ->
                    new SourceEvidencePolicy(SourceEvidencePolicy.VERSION, List.of(), Set.of(), Optional.empty()));
            GitPreparedComparison comparison = git.prepareComparison(runtime.workingTree(), endpoints.beforeRevision(),
                    endpoints.afterRevision(), beforePolicy, after.policy(),
                    before.flatMap(source -> source.guide().path()).stream().collect(Collectors.toSet()),
                    after.guide().path().stream().collect(Collectors.toSet()));
            evidence.publishComparison(requiredJob, comparison, Instant.now(),
                    new GitEvidenceOwnership(GitPublicationScope.REVIEW, Optional.of(payload.reviewId())), before, after);
        } catch (RuntimeException exception) {
            evidence.fail(requiredJob);
            throw exception;
        } finally {
            runtime.lock().writeLock().unlock();
        }
    }

    private void validateCheckout(RepositoryRuntime runtime) {
        try {
            runtime.managedCheckout().validateBoundary(runtime.workingTree());
        } catch (IOException exception) {
            throw new RepositoryMutationException("managed Git evidence checkout boundary is invalid", exception);
        }
    }

    private void metadata(IndexJob job, RepositoryRuntime runtime) {
        String branch = job.gitEvidence().orElseThrow().branch().orElseThrow();
        if (!git.isCloned(runtime.workingTree())) {
            git.clone(runtime.workingTree(), runtime.remoteUrl());
        }
        List<GitBranch> branches = git.fetchRemoteBranches(runtime.workingTree(), runtime.remoteUrl());
        RepositoryRevision head = branches.stream().filter(candidate -> candidate.name().equals(branch))
                .map(GitBranch::head).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("requested metadata branch is unavailable"));
        Instant observedAt = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        GitCatalogManifest catalog = evidence.beginCatalog(job, observedAt);
        evidence.appendBranches(catalog, branches);
        GitHistoryManifest history = evidence.beginHistory(job, catalog.catalogId(), branch, head, observedAt);
        AtomicLong ordinal = new AtomicLong();
        git.streamReachableHistory(runtime.workingTree(), head,
                commit -> evidence.appendCommit(history, ordinal.getAndIncrement(), commit));
        evidence.readyHistory(history, ordinal.get());
        evidence.publishMetadata(job, new GitHistoryManifest(history.historyId(), catalog.catalogId(),
                job.repositoryId(), branch, head, observedAt, GitEvidenceState.READY,
                GitHistoryManifest.VERSION, ordinal.get(), GitEvidenceOwnership.standalone()));
    }
}
