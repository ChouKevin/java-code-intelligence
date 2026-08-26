package com.java.semantic.indexer.build;

import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.JGitRevisionDiffAdapter;
import com.java.semantic.indexer.incremental.ModuleLocator;
import com.java.semantic.indexer.incremental.SourceContractChangeDetector;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.uat.PublicationGate;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Assembles the real, bounded resources for one BUILD job. The JGit handle stays open for the
 * exact-revision diff and the JDT workspace is invalidated once the scope ends.
 */
public final class RepositoryBuildScopeFactory implements RepositoryBuildRunner.BuildScopeFactory {
    private final IndexBuildService.CheckoutResolver checkout;
    private final MongoTemplate template;
    private final IndexJobStore jobs;
    private final PublicationPort publication;
    private final PublicationGate publicationGate;
    private final JavaSemanticService semanticService;
    private final JdtWorkspaceManager workspaces;
    private final GitResourceFactory gitResources;

    public RepositoryBuildScopeFactory(IndexBuildService.CheckoutResolver checkout, MongoTemplate template,
                                       IndexJobStore jobs, PublicationPort publication, PublicationGate publicationGate,
                                       JavaSemanticService semanticService, JdtWorkspaceManager workspaces) {
        this(checkout, template, jobs, publication, publicationGate, semanticService, workspaces, RepositoryBuildScopeFactory::openGit);
    }

    RepositoryBuildScopeFactory(IndexBuildService.CheckoutResolver checkout, MongoTemplate template,
                                IndexJobStore jobs, PublicationPort publication, PublicationGate publicationGate,
                                JavaSemanticService semanticService, JdtWorkspaceManager workspaces,
                                GitResourceFactory gitResources) {
        this.checkout = Objects.requireNonNull(checkout, "checkout is required");
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.publication = Objects.requireNonNull(publication, "publication is required");
        this.publicationGate = Objects.requireNonNull(publicationGate, "publication gate is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semantic service is required");
        this.workspaces = Objects.requireNonNull(workspaces, "JDT workspaces are required");
        this.gitResources = Objects.requireNonNull(gitResources, "Git resources are required");
    }

    @Override
    public RepositoryBuildRunner.BuildScope open(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "job is required");
        IndexBuildService.CheckedOutRepository selectedCheckout = checkout.checkout(requiredJob);
        Git git = gitResources.open(selectedCheckout.root());
        try {
            return new JobBuildScope(requiredJob, buildService(git), workspaces, git);
        } catch (RuntimeException exception) {
            closeAfterFailedOpen(git, requiredJob.repositoryId());
            throw exception;
        }
    }

    private IndexBuildService buildService(Git git) {
        MongoGenerationWriter generationWriter = new MongoGenerationWriter(template);
        SourceIndexBatchDocumentMapper documentMapper = new SourceIndexBatchDocumentMapper(template.getConverter());
        IncrementalIndexPlanner incrementalPlanner = new IncrementalIndexPlanner(
                new JGitRevisionDiffAdapter(git.getRepository()), SourceContractChangeDetector.lightweight(),
                conservativeModuleLocator());
        IncrementalGenerationBuilder incrementalBuilder = new IncrementalGenerationBuilder(template, incrementalPlanner,
                new ParentGenerationCopier(template, generationWriter));
        return new IndexBuildService(new FullIndexPlanner(), new JdtLsRepositoryIndexExporter(semanticService),
                generationWriter, documentMapper, new GenerationValidator(template), checkout, incrementalBuilder, jobs, publication, publicationGate);
    }

    /**
     * Build-descriptor ownership is intentionally unknown, so the incremental planner must choose
     * a full rebuild instead of copying facts from a possibly affected module.
     */
    static ModuleLocator conservativeModuleLocator() {
        return ConservativeModuleLocator.INSTANCE;
    }

    private static Git openGit(Path repositoryRoot) {
        try {
            return Git.open(repositoryRoot.toFile());
        } catch (IOException exception) {
            throw new RepositoryMutationException("unable to open exact checked-out repository", exception);
        }
    }

    private void closeAfterFailedOpen(Git git, RepositoryId repositoryId) {
        try {
            git.close();
        } finally {
            workspaces.invalidate(repositoryId);
        }
    }

    @FunctionalInterface
    interface GitResourceFactory {
        Git open(Path repositoryRoot);
    }

    private static final class JobBuildScope implements RepositoryBuildRunner.BuildScope {
        private final IndexJob job;
        private final IndexBuildService buildService;
        private final JdtWorkspaceManager workspaces;
        private final Git git;

        private JobBuildScope(IndexJob job, IndexBuildService buildService, JdtWorkspaceManager workspaces, Git git) {
            this.job = Objects.requireNonNull(job, "job is required");
            this.buildService = Objects.requireNonNull(buildService, "build service is required");
            this.workspaces = Objects.requireNonNull(workspaces, "JDT workspaces are required");
            this.git = Objects.requireNonNull(git, "Git resource is required");
        }

        @Override
        public void build() {
            buildService.build(job);
        }

        @Override
        public void close() {
            try {
                git.close();
            } finally {
                workspaces.invalidate(job.repositoryId());
            }
        }
    }

    private enum ConservativeModuleLocator implements ModuleLocator {
        INSTANCE;

        @Override
        public Optional<String> locate(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<java.util.Set<String>> reverseDependencyClosure(String module) {
            return Optional.empty();
        }

        @Override
        public Optional<java.util.Set<String>> supportedSources(String module) {
            return Optional.empty();
        }
    }
}
