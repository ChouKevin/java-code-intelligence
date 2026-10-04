package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobNotFoundException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Single dispatcher worker: pin once, export exact Git objects, seal, publish, complete. */
@Component
public final class RepositorySourceManager {
    private final FileSourceJobStore jobs;
    private final RepositoryRevisionResolver resolver;
    private final JGitRevisionExporter exporter;
    private final SourcePublicationStore publications;
    private final RepositoryRegistry registry;
    private final Path stagingRoot;

    public RepositorySourceManager(FileSourceJobStore jobs, RepositoryRevisionResolver resolver,
            JGitRevisionExporter exporter, SourcePublicationStore publications, RepositoryRegistry registry,
            RepositoryProperties properties) {
        this.jobs = jobs;
        this.resolver = resolver;
        this.exporter = exporter;
        this.publications = publications;
        this.registry = registry;
        stagingRoot = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize().resolve("staging");
    }

    public SourcePreparationJob execute(SourcePreparationJob claimed) {
        RepositoryId repository = new RepositoryId(claimed.repositoryId());
        IndexJobId id = new IndexJobId(claimed.jobId());
        registry.requireOrigin(repository, claimed.originFingerprint());
        try {
            if (claimed.resolvedRevision().isEmpty() && claimed.requestedRevision().isEmpty()
                    && !registry.require(repository).getDefaultBranch().equals(claimed.defaultBranch())) {
                throw new IllegalStateException("configured branch changed since acceptance");
            }
            SourceRepositoryState previous = publications.state(repository);
            Optional<RepositoryRevision> requested = claimed.requestedRevision().map(RepositoryRevision::ofSha);
            Optional<RepositoryRevision> previouslyPublished = requested.filter(revision ->
                    previous.published().containsKey(revision.value()));
            RepositoryRevision revision = claimed.resolvedRevision().map(RepositoryRevision::ofSha)
                    .or(() -> previouslyPublished).orElseGet(() -> resolver.resolve(repository, requested));
            SourcePreparationJob pinned = jobs.recordResolved(repository, id, revision);
            SourceContext context = new SourceContext(repository.value(), revision.value());
            PreparedRevision existing = previous.published().get(revision.value());
            SourceRevisionManifest manifest;
            if (java.util.Objects.nonNull(existing)) {
                manifest = publications.manifest(context, existing.manifestDigest());
            } else {
                DurableSourceFiles.ensureDirectories(stagingRoot.getParent(), stagingRoot,
                        DurableSourceFiles.Visibility.PRIVATE);
                Path staging = stagingRoot.resolve(id.value());
                Files.createDirectory(staging, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                manifest = exporter.export(repository, revision, staging);
                publications.seal(pinned, staging, manifest);
            }
            PreparedRevision receipt = publications.publish(pinned, manifest);
            return jobs.complete(repository, id, receipt);
        } catch (Exception exception) {
            SourcePreparationJob persisted = jobs.find(repository, id).orElseThrow(IndexJobNotFoundException::new);
            Optional<PreparedRevision> committed = publications.lookupPublishedJob(persisted);
            if (committed.isPresent()) {
                return jobs.complete(repository, id, committed.orElseThrow());
            }
            org.slf4j.LoggerFactory.getLogger(RepositorySourceManager.class)
                    .warn("Source preparation failed for repository {}", repository.value());
            SourcePreparationJob failed = jobs.fail(repository, id, "PREPARATION_FAILED");
            publications.updatePreparation(failed);
            return failed;
        }
    }
}
