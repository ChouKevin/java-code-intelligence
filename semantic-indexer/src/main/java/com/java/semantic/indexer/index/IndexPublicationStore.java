package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Membership and receipt commit together; directories are never recovery authority. */
public final class IndexPublicationStore {
    private final Path admin;
    private final Path published;
    private final ObjectMapper mapper;
    private final SourcePublicationStore sources;
    private final RepositoryRegistry registry;

    public IndexPublicationStore(RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles ownership, SourcePublicationStore sources, RepositoryRegistry registry) {
        this.admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        this.published = Path.of(properties.getIndexPublishedRoot()).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.sources = sources;
        this.registry = registry;
        Objects.requireNonNull(ownership);
    }

    public synchronized IndexRepositoryState state(RepositoryId repository) {
        String origin = registry.origin(repository);
        Path file = published.resolve(repository.value()).resolve("state.json");
        try {
            IndexFiles.safe(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                return new IndexRepositoryState(IndexContract.FORMAT_VERSION, repository.value(), origin, Map.of());
            IndexRepositoryState state = mapper.readValue(IndexFiles.read(file, IndexFiles.MAX_STATE), IndexRepositoryState.class);
            if (state.formatVersion() != IndexContract.FORMAT_VERSION || !state.repositoryId().equals(repository.value())
                    || !state.originFingerprint().equals(origin)) throw new IOException("index state identity mismatch");
            return state;
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    public synchronized Optional<Receipt> selected(SourceContext context) {
        RevisionIndexes revision = state(new RepositoryId(context.repositoryId())).revisions().get(context.revision());
        if (Objects.isNull(revision) || revision.selectedGeneration().isEmpty()) return Optional.empty();
        Receipt receipt = revision.generations().get(revision.selectedGeneration().orElseThrow());
        requireSource(receipt.manifest());
        validate(generationPath(receipt.manifest()), receipt.manifest(), Optional.of(receipt.manifestDigest()));
        return Optional.of(receipt);
    }

    public synchronized Receipt publish(SemanticIndexJob job, Path staging, IndexManifest manifest) {
        requireIntent(job, manifest);
        if (!manifest.profile().equals(IndexContract.currentProfile()))
            throw new IndexFailure(IndexFailure.Code.INDEX_INCOMPATIBLE);
        RepositoryId repository = new RepositoryId(job.repositoryId());
        synchronized (sources) {
            requireCurrent(job);
            IndexRepositoryState previous = state(repository);
            RevisionIndexes old = previous.revisions().get(job.revision());
            Optional<String> selected = Objects.isNull(old) ? Optional.empty() : old.selectedGeneration();
            if (!selected.equals(job.expectedSelection())) throw new IllegalStateException("index selection changed since acceptance");
            if (Objects.nonNull(old) && !old.sourceManifestDigest().equals(job.sourceManifestDigest()))
                throw new IllegalStateException("source membership changed since acceptance");
            Path stage = staging.toAbsolutePath().normalize();
            Path expected = admin.resolve("index/staging").resolve(job.indexGeneration());
            Path destination = generationPath(manifest);
            try {
                if (!stage.equals(expected)) throw new IOException("index staging identity mismatch");
                IndexFiles.safe(stage); IndexFiles.safe(destination);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("generation directory is not adoptable");
                String digest = validate(stage, manifest, Optional.empty());
                DurableSourceFiles.preparePublishedTree(stage);
                DurableSourceFiles.forceTree(stage);
                DurableSourceFiles.ensureDirectories(published, destination.getParent(), DurableSourceFiles.Visibility.PUBLISHED);
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
                DurableSourceFiles.forceDirectory(destination.getParent());
                Receipt receipt = new Receipt(manifest, digest, job.jobId(), job.requestId());
                Map<String, Receipt> generations = new HashMap<>(Objects.isNull(old) ? Map.of() : old.generations());
                generations.put(job.indexGeneration(), receipt);
                Map<String, RevisionIndexes> revisions = new HashMap<>(previous.revisions());
                revisions.put(job.revision(), new RevisionIndexes(job.sourceManifestDigest(), Optional.of(job.indexGeneration()), generations));
                replace(new IndexRepositoryState(IndexContract.FORMAT_VERSION, repository.value(), job.originFingerprint(), revisions));
                return receipt;
            } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
        }
    }

    public synchronized Optional<Receipt> receipt(SemanticIndexJob job) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        RevisionIndexes revision = state(new RepositoryId(job.repositoryId())).revisions().get(job.revision());
        if (Objects.isNull(revision)) return Optional.empty();
        Receipt receipt = revision.generations().get(job.indexGeneration());
        if (Objects.isNull(receipt)) return Optional.empty();
        if (!receipt.jobId().equals(job.jobId()) || !receipt.requestId().equals(job.requestId()))
            throw new IllegalStateException("index receipt private identity mismatch");
        requireIntentFields(job, receipt.manifest());
        requireSource(receipt.manifest());
        validate(generationPath(receipt.manifest()), receipt.manifest(), Optional.of(receipt.manifestDigest()));
        return Optional.of(receipt);
    }

    /** Caller owns the repository exclusive read guard and a durable deletion intent. */
    public synchronized void withdraw(Receipt expected, boolean sourceDeleted) {
        IndexManifest manifest = expected.manifest();
        RepositoryId repository = new RepositoryId(manifest.context().repositoryId());
        registry.requireOrigin(repository, manifest.originFingerprint());
        PreparedRevision source = sources.state(repository).published().get(manifest.context().revision());
        if (sourceDeleted ? Objects.nonNull(source)
                : Objects.isNull(source) || !source.manifestDigest().equals(manifest.sourceManifestDigest())) {
            throw new IllegalStateException("index deletion source precondition changed");
        }
        IndexRepositoryState current = state(repository);
        RevisionIndexes revision = current.revisions().get(manifest.context().revision());
        if (Objects.isNull(revision)) return;
        if (!revision.sourceManifestDigest().equals(manifest.sourceManifestDigest()))
            throw new IllegalStateException("index deletion source identity changed");
        String generation = manifest.indexGeneration();
        Receipt member = revision.generations().get(generation);
        if (Objects.isNull(member)) return;
        if (!member.equals(expected) || !sourceDeleted && revision.selectedGeneration().filter(generation::equals).isPresent())
            throw new IllegalStateException("index deletion target changed or remains selected");
        Map<String, Receipt> generations = new HashMap<>(revision.generations());
        generations.remove(generation);
        Map<String, RevisionIndexes> revisions = new HashMap<>(current.revisions());
        if (generations.isEmpty()) revisions.remove(manifest.context().revision());
        else revisions.put(manifest.context().revision(), new RevisionIndexes(revision.sourceManifestDigest(),
                revision.selectedGeneration().filter(value -> !value.equals(generation)), generations));
        try {
            replace(new IndexRepositoryState(current.formatVersion(), current.repositoryId(), current.originFingerprint(), revisions));
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    /** New work only; committed receipt recovery deliberately uses requireSource instead. */
    public void requireCurrent(SemanticIndexJob job) {
        synchronized (sources) {
            SourceRepositoryState state = sources.state(new RepositoryId(job.repositoryId()));
            if (state.current().filter(current -> current.revision().equals(job.revision())
                    && current.manifestDigest().equals(job.sourceManifestDigest())).isEmpty()) {
                throw new IndexFailure(IndexFailure.Code.INDEX_NOT_CURRENT);
            }
            PreparedRevision member = state.published().get(job.revision());
            if (Objects.isNull(member) || !member.manifestDigest().equals(job.sourceManifestDigest())
                    || !sources.manifest(member.context(), member.manifestDigest()).inventoryDigest().equals(job.inventoryDigest())) {
                throw new IndexFailure(IndexFailure.Code.INDEX_NOT_CURRENT);
            }
            registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        }
    }

    private void requireSource(IndexManifest manifest) {
        PreparedRevision receipt = sources.state(new RepositoryId(manifest.context().repositoryId())).published().get(manifest.context().revision());
        if (Objects.isNull(receipt) || !receipt.manifestDigest().equals(manifest.sourceManifestDigest())
                || !sources.manifest(manifest.context(), receipt.manifestDigest()).inventoryDigest().equals(manifest.inventoryDigest()))
            throw new IllegalStateException("index source publication mismatch");
    }

    private String validate(Path path, IndexManifest manifest, Optional<String> expectedDigest) {
        try {
            IndexFiles.safe(path);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("sealed index missing");
            byte[] bytes = IndexFiles.read(path.resolve("manifest.json"), IndexFiles.MAX_MANIFEST);
            String digest = DurableSourceFiles.sha256(bytes);
            if (expectedDigest.filter(value -> !value.equals(digest)).isPresent()
                    || !mapper.readValue(bytes, IndexManifest.class).equals(manifest)
                    || manifest.formatVersion() != IndexContract.FORMAT_VERSION
                    || !IndexContract.supportedProfile(manifest.profile())) throw new IOException("index manifest mismatch");
            Path database = path.resolve("index.sqlite");
            SqliteIndexBuilder.noSidecars(database);
            if (manifest.databaseBytes() > IndexContract.MAX_DATABASE_BYTES || Files.size(database) != manifest.databaseBytes()
                    || !IndexFiles.digest(database).equals(manifest.databaseDigest()))
                throw new IOException("index database digest mismatch");
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
                for (Path file : files) {
                    if (!file.getFileName().toString().equals("manifest.json") && !file.getFileName().toString().equals("index.sqlite"))
                        throw new IOException("unexpected sealed index member");
                }
            }
            return digest;
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    private static void requireIntent(SemanticIndexJob job, IndexManifest manifest) {
        if (job.phase() != SemanticIndexJob.Phase.RUNNING) throw new IllegalStateException("index publication requires running job");
        requireIntentFields(job, manifest);
    }
    private static void requireIntentFields(SemanticIndexJob job, IndexManifest manifest) {
        if (!manifest.context().equals(new SourceContext(job.repositoryId(), job.revision()))
                || !manifest.originFingerprint().equals(job.originFingerprint()) || !manifest.indexGeneration().equals(job.indexGeneration())
                || !manifest.sourceManifestDigest().equals(job.sourceManifestDigest()) || !manifest.inventoryDigest().equals(job.inventoryDigest()))
            throw new IllegalStateException("index manifest differs from accepted intent");
    }
    private Path generationPath(IndexManifest manifest) {
        return published.resolve(manifest.context().repositoryId()).resolve("revisions").resolve(manifest.context().revision()).resolve(manifest.indexGeneration());
    }
    private void replace(IndexRepositoryState state) throws IOException {
        Path file = published.resolve(state.repositoryId()).resolve("state.json");
        IndexFiles.safe(file);
        DurableSourceFiles.ensureDirectories(published, file.getParent(), DurableSourceFiles.Visibility.PUBLISHED);
        DurableSourceFiles.atomicBytes(file, mapper.writeValueAsBytes(state), IndexFiles.MAX_STATE, DurableSourceFiles.Visibility.PUBLISHED);
    }
}
