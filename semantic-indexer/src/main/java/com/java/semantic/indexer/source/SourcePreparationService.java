package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestNotFoundException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Durable original-request acceptance; never retries or replays an unknown outcome. */
@Service
public final class SourcePreparationService {
    private final FileSourceJobStore jobs;
    private final SourcePublicationStore publications;
    private final RepositoryRegistry repositories;

    public SourcePreparationService(FileSourceJobStore jobs, SourcePublicationStore publications,
            RepositoryRegistry repositories) {
        this.jobs = jobs;
        this.publications = publications;
        this.repositories = repositories;
    }

    public SourcePreparationJob prepareSource(RepositoryId repository, PreparationRequestId request,
            Optional<RepositoryRevision> revision) {
        String branch = repositories.require(repository).getDefaultBranch();
        SourcePreparationJob accepted = jobs.admit(repository, request, revision, branch,
                publications.state(repository).current());
        publications.updatePreparation(accepted);
        return accepted;
    }

    public SourcePreparationJob getJob(RepositoryId repository, Optional<IndexJobId> id,
            Optional<PreparationRequestId> request) {
        repositories.require(repository);
        if (id.isPresent() == request.isPresent()) {
            throw new IllegalArgumentException("exactly one job selector is required");
        }
        if (request.isPresent()) {
            return jobs.find(repository, request.orElseThrow()).orElseThrow(PreparationRequestNotFoundException::new);
        }
        return jobs.find(repository, id.orElseThrow()).orElseThrow(IndexJobNotFoundException::new);
    }
}
