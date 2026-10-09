package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.file.Path;
import java.util.Objects;

/** Separates historical source access from current-only new-index admission. */
public final class IndexSourceAccess {
    private final SourcePublicationStore sources;
    private final RepositoryRegistry repositories;
    private final Path root;

    public IndexSourceAccess(SourcePublicationStore sources, RepositoryRegistry repositories, RepositoryProperties properties) {
        this.sources = sources;
        this.repositories = repositories;
        root = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
    }

    public IndexBuildInput currentInput(SourceContext context, String generation) {
        synchronized (sources) {
            SourceRepositoryState state = sources.state(new RepositoryId(context.repositoryId()));
            PreparedRevision member = state.published().get(context.revision());
            if (state.current().filter(current -> current.revision().equals(context.revision())
                    && Objects.nonNull(member) && current.manifestDigest().equals(member.manifestDigest())).isEmpty()) {
                throw new IndexFailure(IndexFailure.Code.INDEX_NOT_CURRENT);
            }
            return input(context, generation);
        }
    }

    public IndexBuildInput input(SourceContext context, String generation) {
        RepositoryId repository = new RepositoryId(context.repositoryId());
        String origin = repositories.origin(repository);
        PreparedRevision receipt = sources.state(repository).published().get(context.revision());
        if (Objects.isNull(receipt)) throw new SourceNotPreparedException();
        SourceRevisionManifest manifest = sources.manifest(context, receipt.manifestDigest());
        Path revision = root.resolve(context.repositoryId()).resolve("revisions").resolve(context.revision());
        return new IndexBuildInput(context, origin, generation, receipt.manifestDigest(), manifest,
                revision.resolve("tree"), revision.resolve("inventory.jsonl"));
    }

    public static final class SourceNotPreparedException extends RuntimeException {
        public SourceNotPreparedException() { super("Exact source must be published before indexing."); }
    }
}
