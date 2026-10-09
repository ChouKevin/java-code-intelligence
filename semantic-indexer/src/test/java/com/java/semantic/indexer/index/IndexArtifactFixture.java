package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.indexer.source.*;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Real Git export and file stores, with a tiny deterministic parser-port test double. */
final class IndexArtifactFixture implements AutoCloseable {
    final ObjectMapper mapper = JsonMapper.builder().build();
    final LocalSourceFixture git;
    final RepositoryProperties properties = new RepositoryProperties();
    final DurableSourceFiles owner;
    final RepositoryRegistry registry;
    final SourcePublicationStore sources;
    final FileSourceJobStore sourceJobs;
    final RepositorySourceManager manager;
    final IndexPublicationStore indexes;
    final FileSemanticIndexJobStore jobs;

    IndexArtifactFixture(Path root) throws Exception {
        git = new LocalSourceFixture(root);
        properties.setSourceAdminRoot(git.admin.toString());
        properties.setSourcePublishedRoot(git.published.toString());
        RepositoryProperties.RepositoryConfig repository = new RepositoryProperties.RepositoryConfig();
        repository.setUrl(git.remote.toUri().toString()); repository.setDisplayName("Orders"); repository.setDefaultBranch("main");
        properties.setRepositories(Map.of("orders", repository));
        owner = new DurableSourceFiles(git.admin);
        registry = new RepositoryRegistry(properties);
        sourceJobs = new FileSourceJobStore(properties, mapper, owner);
        sources = SourceLifecycleFixture.publications(properties, mapper, owner, sourceJobs);
        RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
        manager = new RepositorySourceManager(sourceJobs, resolver, new JGitRevisionExporter(resolver, registry, mapper), sources, registry, properties);
        indexes = new IndexPublicationStore(properties, mapper, owner, sources, registry);
        jobs = new FileSemanticIndexJobStore(properties, mapper, owner);
    }

    IndexBuildInput prepare(String text) throws Exception {
        git.commit("A.java", text.getBytes(StandardCharsets.UTF_8), "source");
        new SourcePreparationService(sourceJobs, sources, registry).prepareSource(git.repository, request(), Optional.empty());
        SourcePreparationJob complete = manager.execute(sourceJobs.claimNext().orElseThrow());
        PreparedRevision receipt = complete.publication().orElseThrow();
        return input(receipt.context());
    }
    IndexBuildInput input(SourceContext context) {
        PreparedRevision receipt = sources.state(git.repository).published().get(context.revision());
        Path revision = git.published.resolve("orders/revisions").resolve(context.revision());
        return new IndexBuildInput(context, registry.origin(git.repository), UUID.randomUUID().toString(), receipt.manifestDigest(),
                sources.manifest(context, receipt.manifestDigest()), revision.resolve("tree"), revision.resolve("inventory.jsonl"));
    }
    Path stage(IndexBuildInput input) { return git.admin.resolve("index/staging").resolve(input.generation()); }
    Path generation(IndexBuildInput input) {
        return Path.of(properties.getIndexPublishedRoot()).resolve("orders/revisions").resolve(input.context().revision()).resolve(input.generation());
    }
    static PreparationRequestId request() { return new PreparationRequestId(UUID.randomUUID().toString()); }
    static ParsedFile parse(String generation, SourceInventoryEntry entry, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int start = text.indexOf("A");
        SourceSpan range = new SourceSpan(0, bytes.length - 1, 1, 1);
        SourceSpan name = new SourceSpan(start, start + 1, 1, 1);
        Declaration declaration = new Declaration(IndexContract.identity(generation, entry.path(), "CLASS", Integer.toString(start)),
                "A", DeclarationKind.CLASS, "", Optional.empty(), entry.path(), entry.blobId().orElseThrow(),
                entry.contentDigest().orElseThrow(), bytes.length, range, name, "class A");
        return new ParsedFile(new FileOutcome(entry.path(), true, ParseStatus.PARSED, entry.byteLength(), entry.blobId(),
                entry.contentDigest(), List.of()), List.of(declaration), List.of(), List.of());
    }
    @Override public void close() throws Exception { owner.close(); git.close(); }
}
