package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Private format-2 namespace, with legacy format-1 reads; mutations are serialized and fsynced. */
public final class FileSemanticIndexJobStore {
    private static final String ATOMIC_WRITE_TEMP_PREFIX = ".source-";
    private static final String ATOMIC_WRITE_TEMP_SUFFIX = ".tmp";
    private final Path admin;
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<String, SemanticIndexJob> acceptedJobs = new LinkedHashMap<>();

    public FileSemanticIndexJobStore(RepositoryProperties properties, ObjectMapper mapper, DurableSourceFiles ownership) {
        this.admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        this.root = admin.resolve("index/jobs");
        this.mapper = Objects.requireNonNull(mapper);
        Objects.requireNonNull(ownership);
    }

    public synchronized SemanticIndexJob admit(IndexBuildInput input, IndexJobId jobId, PreparationRequestId request,
            Optional<String> expectedSelection, IndexAnalysisIntent analysis) {
        RepositoryId repository = new RepositoryId(input.context().repositoryId());
        Optional<SemanticIndexJob> prior = find(repository, request);
        if (prior.isPresent()) throw new PreparationRequestReusedException(new IndexJobId(prior.orElseThrow().jobId()), request);
        if (hasActive(repository)) throw new IndexJobAlreadyActiveException(repository);
        SemanticIndexJob job = new SemanticIndexJob(SemanticIndexJob.FORMAT_VERSION, jobId.value(),
                repository.value(), request.value(), input.context().revision(), input.originFingerprint(), input.sourceManifestDigest(),
                input.sourceManifest().inventoryDigest(), input.generation(), expectedSelection, SemanticIndexJob.Phase.ACCEPTED,
                Instant.now(), Optional.empty(), Optional.empty(), Optional.of(analysis));
        scan(repository, existing -> {
            if (existing.indexGeneration().equals(job.indexGeneration())) throw new IllegalStateException("generation identity reused");
        });
        save(job);
        acceptedJobs.put(job.repositoryId(), job);
        return job;
    }

    public synchronized Optional<SemanticIndexJob> find(RepositoryId repository, PreparationRequestId request) {
        Path path = root.resolve(repository.value()).resolve(request.value() + ".json");
        try {
            IndexFiles.safe(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            SemanticIndexJob job = mapper.readValue(IndexFiles.read(path, IndexFiles.MAX_MANIFEST), SemanticIndexJob.class);
            if (!job.repositoryId().equals(repository.value()) || !job.requestId().equals(request.value()))
                throw new IOException("private semantic job identity mismatch");
            return Optional.of(job);
        } catch (IOException | JacksonException exception) { throw IndexFiles.unavailable(exception); }
    }
    public synchronized Optional<SemanticIndexJob> find(RepositoryId repository, IndexJobId id) {
        return matching(repository, job -> job.jobId().equals(id.value()));
    }
    public synchronized boolean hasActive(RepositoryId repository) {
        return matching(repository, FileSemanticIndexJobStore::active).isPresent();
    }
    public synchronized Optional<SemanticIndexJob> claimNext() {
        Optional<SemanticIndexJob> next = acceptedJobs.values().stream()
                .min(Comparator.comparing(SemanticIndexJob::acceptedAt).thenComparing(SemanticIndexJob::jobId));
        if (next.isEmpty()) return Optional.empty();
        SemanticIndexJob running = next.orElseThrow().withPhase(SemanticIndexJob.Phase.RUNNING);
        save(running);
        acceptedJobs.remove(running.repositoryId());
        return Optional.of(running);
    }
    public synchronized SemanticIndexJob complete(RepositoryId repository, IndexJobId id, Receipt receipt) {
        SemanticIndexJob complete = running(repository, id).completed(receipt);
        save(complete);
        return complete;
    }
    public synchronized SemanticIndexJob fail(RepositoryId repository, IndexJobId id, String code) {
        SemanticIndexJob failed = running(repository, id).failed(code);
        save(failed);
        return failed;
    }

    public synchronized void recover(IndexPublicationStore publications, ResolutionInputStore inputs) {
        List<SemanticIndexJob> jobs = new ArrayList<>();
        // Validate every durable identity first, retaining only the bounded active set, not historical payloads.
        all(job -> { if (active(job)) jobs.add(job); });
        acceptedJobs.clear();
        for (SemanticIndexJob job : jobs) {
            if (active(job)) {
                Optional<Receipt> committed = publications.receipt(job);
                if (job.phase() == SemanticIndexJob.Phase.RUNNING)
                    save(committed.map(job::completed).orElseGet(() -> job.failed("WORKER_INTERRUPTED")));
                else if (committed.isPresent()) throw new IllegalStateException("accepted job has an unexpected publication");
                else {
                    try {
                        publications.requireCurrent(job);
                        inputs.requireAccepted(job);
                        acceptedJobs.put(job.repositoryId(), job);
                    } catch (IndexFailure failure) {
                        save(job.failed(failure.code().name()));
                    } catch (PlatformIdentity.EnvironmentChangedException failure) {
                        save(job.failed("ENVIRONMENT_CHANGED"));
                    } catch (IllegalStateException failure) {
                        save(job.failed("INDEX_UNAVAILABLE"));
                    }
                }
            }
        }
    }

    public synchronized void visit(RepositoryId repository, Consumer<SemanticIndexJob> consumer) {
        scan(repository, consumer);
    }

    private Optional<SemanticIndexJob> matching(RepositoryId repository, Predicate<SemanticIndexJob> predicate) {
        SemanticIndexJob[] selected = new SemanticIndexJob[1];
        scan(repository, job -> { if (predicate.test(job)) selected[0] = job; });
        return Optional.ofNullable(selected[0]);
    }

    private SemanticIndexJob running(RepositoryId repository, IndexJobId id) {
        SemanticIndexJob job = find(repository, id).orElseThrow(IndexJobNotFoundException::new);
        if (job.phase() != SemanticIndexJob.Phase.RUNNING) throw new IllegalStateException("semantic job is not running");
        return job;
    }
    private static boolean active(SemanticIndexJob job) {
        return job.phase() == SemanticIndexJob.Phase.ACCEPTED || job.phase() == SemanticIndexJob.Phase.RUNNING;
    }
    // DurableSourceFiles.atomicBytes uses Files.createTempFile(parent, ".source-", ".tmp").
    private static boolean isAtomicWriteTemporaryName(String fileName) {
        return fileName.length() > ATOMIC_WRITE_TEMP_PREFIX.length() + ATOMIC_WRITE_TEMP_SUFFIX.length()
                && fileName.startsWith(ATOMIC_WRITE_TEMP_PREFIX)
                && fileName.endsWith(ATOMIC_WRITE_TEMP_SUFFIX);
    }
    private void all(Consumer<SemanticIndexJob> consumer) {
        try {
            IndexFiles.safe(root);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Set<String> ids = new HashSet<>();
            try (DirectoryStream<Path> repositories = Files.newDirectoryStream(root)) {
                for (Path repository : repositories) {
                    if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid private index repository");
                    scan(new RepositoryId(repository.getFileName().toString()), job -> {
                        if (!ids.add(job.jobId())) throw IndexFiles.unavailable(new IOException("duplicate semantic job identity"));
                        consumer.accept(job);
                    });
                }
            }
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    private void scan(RepositoryId repository, Consumer<SemanticIndexJob> consumer) {
        Path directory = root.resolve(repository.value());
        try {
            IndexFiles.safe(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            Set<String> ids = new HashSet<>();
            Set<String> generations = new HashSet<>();
            int active = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path file : entries) {
                    String fileName = file.getFileName().toString();
                    if (isAtomicWriteTemporaryName(fileName)
                            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    SemanticIndexJob job = mapper.readValue(IndexFiles.read(file, IndexFiles.MAX_MANIFEST), SemanticIndexJob.class);
                    if (!job.repositoryId().equals(repository.value()) || !fileName.equals(job.requestId() + ".json")
                            || !ids.add(job.jobId()) || !generations.add(job.indexGeneration()) || active(job) && ++active > 1)
                        throw new IOException("ambiguous private semantic job records");
                    consumer.accept(job);
                }
            }
        } catch (IOException | JacksonException exception) { throw IndexFiles.unavailable(exception); }
    }
    private void save(SemanticIndexJob job) {
        try {
            Path file = root.resolve(job.repositoryId()).resolve(job.requestId() + ".json");
            IndexFiles.safe(file);
            DurableSourceFiles.ensureDirectories(admin, file.getParent(), DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.atomicBytes(file, mapper.writeValueAsBytes(job), IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
}
