package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Durable fixed targets; no directory discovery or orphan adoption authorizes deletion. */
public final class IndexRetentionStore {
    public record Intent(int formatVersion, String repositoryId, String revision, String originFingerprint,
            String sourceManifestDigest, String gcRunId, boolean sourceDeletion, List<Receipt> targets) {
        public Intent {
            ModelValidation.require(formatVersion == 1, "unsupported index deletion intent");
            repositoryId = new RepositoryId(repositoryId).value();
            revision = RepositoryRevision.ofSha(revision).value();
            originFingerprint = ModelValidation.sha256(originFingerprint, "origin fingerprint");
            sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
            gcRunId = IndexContract.requireGeneration(gcRunId);
            targets = List.copyOf(targets);
            Set<String> generations = new HashSet<>();
            for (Receipt target : targets) {
                IndexManifest manifest = target.manifest();
                ModelValidation.require(manifest.context().repositoryId().equals(repositoryId)
                        && manifest.context().revision().equals(revision) && manifest.originFingerprint().equals(originFingerprint)
                        && manifest.sourceManifestDigest().equals(sourceManifestDigest)
                        && generations.add(manifest.indexGeneration()), "index deletion target identity mismatch");
            }
            ModelValidation.require(sourceDeletion || targets.size() == 1, "obsolete deletion requires one generation");
        }
    }
    private final Path admin;
    private final Path root;
    private final ObjectMapper mapper;
    private final RepositoryRegistry registry;

    public IndexRetentionStore(RepositoryProperties properties, ObjectMapper mapper, DurableSourceFiles ownership,
            RepositoryRegistry registry) {
        admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        root = admin.resolve("index/retention");
        this.mapper = mapper;
        this.registry = registry;
        Objects.requireNonNull(ownership);
    }

    public synchronized Optional<Intent> pending(RepositoryId repository) {
        Path path = root.resolve(repository.value() + ".json");
        try {
            IndexFiles.safe(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Intent intent = mapper.readValue(IndexFiles.read(path, IndexFiles.MAX_STATE), Intent.class);
            if (!intent.repositoryId().equals(repository.value())) throw new IOException("index deletion repository mismatch");
            registry.requireOrigin(repository, intent.originFingerprint());
            return Optional.of(intent);
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    public synchronized void begin(Intent intent) {
        RepositoryId repository = new RepositoryId(intent.repositoryId());
        registry.requireOrigin(repository, intent.originFingerprint());
        Optional<Intent> previous = pending(repository);
        if (previous.isPresent()) {
            if (!previous.orElseThrow().equals(intent)) throw new IllegalStateException("index deletion already pending");
            return;
        }
        try {
            IndexFiles.safe(root);
            DurableSourceFiles.ensureDirectories(admin, root, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.atomicBytes(root.resolve(repository.value() + ".json"), mapper.writeValueAsBytes(intent),
                    IndexFiles.MAX_STATE, DurableSourceFiles.Visibility.PRIVATE);
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    public synchronized void finish(Intent intent) {
        RepositoryId repository = new RepositoryId(intent.repositoryId());
        if (pending(repository).filter(intent::equals).isEmpty()) throw new IllegalStateException("index deletion intent changed");
        try {
            Files.delete(root.resolve(repository.value() + ".json"));
            DurableSourceFiles.forceDirectory(root);
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }
}
