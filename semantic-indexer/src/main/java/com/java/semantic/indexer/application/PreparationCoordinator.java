package com.java.semantic.indexer.application;

import com.java.semantic.indexer.index.FileSemanticIndexJobStore;
import com.java.semantic.indexer.index.IndexBuildInput;
import com.java.semantic.indexer.index.IndexPublicationStore;
import com.java.semantic.indexer.index.IndexSourceAccess;
import com.java.semantic.indexer.index.SemanticIndexJob;
import com.java.semantic.indexer.index.IndexAnalysisIntent;
import com.java.semantic.indexer.index.ResolutionInputStore;
import com.java.semantic.indexer.source.SourceCapacityGuard;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationJob;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePreparationService;
import com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One durable request namespace and repository-wide admission fence for both operations. */
@Service
public final class PreparationCoordinator {
    private final FileSourceJobStore sources;
    private final FileSemanticIndexJobStore indexes;
    private final SourcePreparationService sourceService;
    private final IndexSourceAccess sourceAccess;
    private final IndexPublicationStore publications;
    private final RepositoryRegistry repositories;
    private final ResolutionInputStore inputs;
    private final SourceCapacityGuard capacity;

    public PreparationCoordinator(FileSourceJobStore sources, FileSemanticIndexJobStore indexes,
            SourcePreparationService sourceService, IndexSourceAccess sourceAccess,
            IndexPublicationStore publications, RepositoryRegistry repositories, ResolutionInputStore inputs,
            RepositoryProperties properties) {
        this.sources = sources;
        this.indexes = indexes;
        this.sourceService = sourceService;
        this.sourceAccess = sourceAccess;
        this.publications = publications;
        this.repositories = repositories;
        this.inputs = inputs;
        this.capacity = new SourceCapacityGuard(properties);
    }

    public SourcePreparationJob prepareSource(RepositoryId repository, PreparationRequestId request,
            Optional<RepositoryRevision> revision) {
        synchronized (sources) {
            admit(repository, request);
            return sourceService.prepareSource(repository, request, revision);
        }
    }

    public SemanticIndexJob prepareSemanticIndex(RepositoryId repository, PreparationRequestId request,
            RepositoryRevision revision) {
        synchronized (sources) {
            admit(repository, request);
            IndexBuildInput input = sourceAccess.currentInput(new SourceContext(repository.value(), revision.value()), UUID.randomUUID().toString());
            RevisionIndexes previous = publications.state(repository).revisions().get(revision.value());
            if (Objects.nonNull(previous) && !previous.sourceManifestDigest().equals(input.sourceManifestDigest())) {
                throw new IllegalStateException("index source binding changed");
            }
            // Read selection metadata only: rebuilding an incompatible or corrupt artifact must remain possible.
            Optional<String> expected = Objects.isNull(previous) ? Optional.empty() : previous.selectedGeneration();
            IndexJobId jobId = IndexJobId.create();
            IndexAnalysisIntent analysis;
            try {
                analysis = inputs.freeze(input, jobId, request, repositories.require(repository).getRelations(), capacity::check);
            } catch (RuntimeException failure) {
                try { inputs.cleanupUnaccepted(jobId, request); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return indexes.admit(input, jobId, request, expected, analysis);
        }
    }

    public PreparationJob getJob(RepositoryId repository, Optional<IndexJobId> id,
            Optional<PreparationRequestId> request) {
        synchronized (sources) {
            repositories.require(repository);
            if (id.isPresent() == request.isPresent()) throw new IllegalArgumentException("exactly one selector is required");
            Optional<SourcePreparationJob> source = request.isPresent()
                    ? sources.find(repository, request.orElseThrow()) : sources.find(repository, id.orElseThrow());
            Optional<SemanticIndexJob> index = request.isPresent()
                    ? indexes.find(repository, request.orElseThrow()) : indexes.find(repository, id.orElseThrow());
            requireUnambiguous(source, index);
            if (source.isPresent()) return source.orElseThrow();
            if (index.isPresent()) return index.orElseThrow();
            if (request.isPresent()) throw new PreparationRequestNotFoundException();
            throw new IndexJobNotFoundException();
        }
    }

    /** Called before recovery mutates RUNNING records, so ambiguity cannot be erased by recovery. */
    public void validateStartup() {
        synchronized (sources) {
            for (SourceRepositoryDescriptor descriptor : repositories.descriptors()) {
                RepositoryId repository = new RepositoryId(descriptor.repositoryId());
                if (sources.hasActive(repository) && indexes.hasActive(repository)) throw ambiguous();
                sources.visit(repository, source -> {
                    repositories.requireOrigin(repository, source.originFingerprint());
                    if (indexes.find(repository, new PreparationRequestId(source.requestId())).isPresent()
                            || indexes.find(repository, new IndexJobId(source.jobId())).isPresent()) throw ambiguous();
                });
                indexes.visit(repository, index -> repositories.requireOrigin(repository, index.originFingerprint()));
            }
        }
    }

    /** GC and HTTP admission share this fence; a failed terminal write must not expose its receipt to GC. */
    public void collectIfIdle(Runnable collection) {
        synchronized (sources) {
            for (SourceRepositoryDescriptor descriptor : repositories.descriptors()) {
                RepositoryId repository = new RepositoryId(descriptor.repositoryId());
                if (sources.hasActive(repository) || indexes.hasActive(repository) || inputs.hasLiveWorker(repository)) return;
            }
            collection.run();
        }
    }

    private void admit(RepositoryId repository, PreparationRequestId request) {
        repositories.require(repository);
        Optional<SourcePreparationJob> source = sources.find(repository, request);
        Optional<SemanticIndexJob> index = indexes.find(repository, request);
        requireUnambiguous(source, index);
        if (source.isPresent()) throw new PreparationRequestReusedException(new IndexJobId(source.orElseThrow().jobId()), request);
        if (index.isPresent()) throw new PreparationRequestReusedException(new IndexJobId(index.orElseThrow().jobId()), request);
        if (sources.hasActive(repository) || indexes.hasActive(repository) || inputs.hasLiveWorker(repository)) throw new IndexJobAlreadyActiveException(repository);
    }

    private static void requireUnambiguous(Optional<SourcePreparationJob> source, Optional<SemanticIndexJob> index) {
        if (source.isPresent() && index.isPresent()) throw ambiguous();
    }
    private static IllegalStateException ambiguous() { return new IllegalStateException("ambiguous preparation records"); }
}
