package com.java.semantic.indexer.job;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** One on-disk file per original request; streaming scans retain no historical job cache. */
@Component
public final class FileSourceJobStore {
    private static final long MAX_JOB_BYTES = 64 * 1024;
    private final Path jobsRoot;
    private final ObjectMapper mapper;
    private final Map<String, SourcePreparationJob> acceptedJobs = new LinkedHashMap<>();
    private final Map<String, SourcePreparationJob> pendingStatus = new HashMap<>();

    public FileSourceJobStore(com.java.semantic.repository.config.RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles ownership) {
        this.jobsRoot = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize().resolve("jobs");
        this.mapper = mapper;
    }

    public synchronized SourcePreparationJob admit(RepositoryId repository, PreparationRequestId request,
            Optional<RepositoryRevision> revision, String defaultBranch, Optional<CurrentPublication> current) {
        Optional<SourcePreparationJob> previous = find(repository, request);
        if (previous.isPresent()) {
            throw new PreparationRequestReusedException(new IndexJobId(previous.orElseThrow().jobId()), request);
        }
        try {
            DurableSourceFiles.ensureDirectories(jobsRoot.getParent(), jobsRoot.resolve(repository.value()),
                    DurableSourceFiles.Visibility.PRIVATE);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(jobsRoot.resolve(repository.value()), "*.json")) {
                for (Path path : entries) {
                    SourcePreparationJob existing = read(path);
                    if (existing.phase() == SourcePreparationJob.Phase.ACCEPTED
                            || existing.phase() == SourcePreparationJob.Phase.RUNNING) {
                        throw new IndexJobAlreadyActiveException(repository);
                    }
                }
            }
            SourcePreparationJob job = new SourcePreparationJob(SourceRevisionManifest.FORMAT_VERSION,
                    IndexJobId.create().value(), repository.value(), request.value(), revision.map(RepositoryRevision::value),
                    defaultBranch, SourcePreparationJob.Phase.ACCEPTED, Instant.now(), Optional.empty(), current,
                    Optional.empty(), Optional.empty());
            save(job);
            acceptedJobs.put(repository.value(), job);
            return job;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public Optional<SourcePreparationJob> find(RepositoryId repository, PreparationRequestId request) {
        Path path = file(repository.value(), request.value());
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            SourcePreparationJob job = read(path);
            if (!job.repositoryId().equals(repository.value()) || !job.requestId().equals(request.value())) {
                throw new IOException("job identity mismatch");
            }
            return Optional.of(job);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public Optional<SourcePreparationJob> find(RepositoryId repository, IndexJobId id) {
        Path directory = jobsRoot.resolve(repository.value());
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : entries) {
                SourcePreparationJob job = read(path);
                if (job.jobId().equals(id.value()) && job.repositoryId().equals(repository.value())) {
                    return Optional.of(job);
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public synchronized Optional<SourcePreparationJob> claimNext() {
        Iterator<SourcePreparationJob> queued = acceptedJobs.values().iterator();
        if (!queued.hasNext()) {
            return Optional.empty();
        }
        SourcePreparationJob accepted = queued.next();
        SourcePreparationJob claimed = accepted.withPhase(SourcePreparationJob.Phase.RUNNING);
        save(claimed);
        queued.remove();
        return Optional.of(claimed);
    }

    public synchronized SourcePreparationJob recordResolved(RepositoryId repository, IndexJobId id,
            RepositoryRevision revision) {
        SourcePreparationJob job = running(repository, id);
        SourcePreparationJob pinned = job.withRevision(revision);
        save(pinned);
        return pinned;
    }

    public synchronized SourcePreparationJob complete(RepositoryId repository, IndexJobId id, PreparedRevision receipt) {
        SourcePreparationJob job = running(repository, id);
        if (job.resolvedRevision().isEmpty() || !receipt.context().revision().equals(job.resolvedRevision().orElseThrow())
                || !receipt.context().repositoryId().equals(repository.value())) {
            throw new IllegalStateException("publication proof does not match resolved job");
        }
        SourcePreparationJob complete = job.completed(receipt);
        save(complete);
        pendingStatus.remove(repository.value());
        return complete;
    }

    public synchronized SourcePreparationJob fail(RepositoryId repository, IndexJobId id, String code) {
        SourcePreparationJob failed = running(repository, id).failed(code);
        save(failed);
        pendingStatus.put(repository.value(), failed);
        return failed;
    }

    public synchronized Optional<SourcePreparationJob> pendingStatus() {
        for (SourcePreparationJob pending : pendingStatus.values()) {
            return Optional.of(pending);
        }
        return Optional.empty();
    }

    public synchronized void statusPublished(SourcePreparationJob job) {
        pendingStatus.remove(job.repositoryId(), job);
    }

    public synchronized void recover(SourcePublicationStore publications) {
        acceptedJobs.clear();
        if (!Files.isDirectory(jobsRoot)) {
            return;
        }
        try (DirectoryStream<Path> repositories = Files.newDirectoryStream(jobsRoot)) {
            for (Path repository : repositories) {
                if (!Files.isDirectory(repository)) {
                    continue;
                }
                SourcePreparationJob latest = null;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(repository, "*.json")) {
                    for (Path path : entries) {
                        SourcePreparationJob job = read(path);
                        if (job.phase() == SourcePreparationJob.Phase.RUNNING) {
                            Optional<PreparedRevision> publication = publications.lookupPublishedJob(job);
                            SourcePreparationJob recovered = publication.isPresent()
                                    ? job.completed(publication.orElseThrow()) : job.failed("WORKER_INTERRUPTED");
                            save(recovered);
                            job = recovered;
                        }
                        if (java.util.Objects.isNull(latest) || job.acceptedAt().isAfter(latest.acceptedAt())) {
                            latest = job;
                        }
                    }
                }
                if (java.util.Objects.nonNull(latest) && latest.phase() == SourcePreparationJob.Phase.ACCEPTED) {
                    acceptedJobs.put(latest.repositoryId(), latest);
                }
                if (java.util.Objects.nonNull(latest) && (latest.phase() == SourcePreparationJob.Phase.FAILED
                        || latest.phase() == SourcePreparationJob.Phase.COMPLETE)) {
                    pendingStatus.put(latest.repositoryId(), latest);
                    publications.updatePreparation(latest);
                }
            }
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private SourcePreparationJob running(RepositoryId repository, IndexJobId id) {
        SourcePreparationJob job = find(repository, id).orElseThrow(IndexJobNotFoundException::new);
        if (job.phase() != SourcePreparationJob.Phase.RUNNING) {
            throw new IllegalStateException("job is not running");
        }
        return job;
    }

    private Path file(String repository, String request) {
        return jobsRoot.resolve(repository).resolve(request + ".json");
    }

    private SourcePreparationJob read(Path path) throws IOException {
        try {
            return mapper.readValue(DurableSourceFiles.boundedRead(path, MAX_JOB_BYTES), SourcePreparationJob.class);
        } catch (RuntimeException exception) {
            throw new IOException("invalid durable job", exception);
        }
    }

    private void save(SourcePreparationJob job) {
        try {
            DurableSourceFiles.atomicBytes(file(job.repositoryId(), job.requestId()),
                    mapper.writeValueAsBytes(job), MAX_JOB_BYTES, DurableSourceFiles.Visibility.PRIVATE);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private static IllegalStateException unavailable(IOException cause) {
        return new IllegalStateException("source job storage unavailable", cause);
    }
}
