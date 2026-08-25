package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Copies compatible parent facts into a new immutable generation without duplicating source artifacts. */
public final class ParentGenerationCopier {
    private final MongoTemplate template;
    private final MongoGenerationWriter writer;
    private final SourceIndexBatchDocumentMapper mapper;

    public ParentGenerationCopier(MongoTemplate template, MongoGenerationWriter writer) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.writer = Objects.requireNonNull(writer, "generation writer is required");
        this.mapper = new SourceIndexBatchDocumentMapper(template.getConverter());
    }

    /** Re-materializes generation-scoped records and revision-scoped fact IDs; global source artifacts are only referenced. */
    public void copy(MongoGenerationWriter.GenerationLease lease, GenerationId parentGeneration,
                     RepositoryRevision parentRevision, RepositoryRevision targetRevision, List<String> copyPaths) {
        Objects.requireNonNull(lease, "generation lease is required");
        Objects.requireNonNull(parentGeneration, "parent generation is required");
        Objects.requireNonNull(parentRevision, "parent revision is required");
        Objects.requireNonNull(targetRevision, "target revision is required");
        for (String path : List.copyOf(Objects.requireNonNull(copyPaths, "copy paths are required")).stream().sorted().toList()) {
            copyPath(lease, parentGeneration, targetRevision, path);
        }
    }

    private void copyPath(MongoGenerationWriter.GenerationLease lease, GenerationId parentGeneration,
                          RepositoryRevision targetRevision, String path) {
        Document scope = new Document("repoId", lease.repositoryId().value()).append("generationId", parentGeneration.value())
                .append("sourcePath", path);
        GenerationFileDocument file = Optional.ofNullable(template.getCollection(IndexCollections.GENERATION_FILES).find(scope).first())
                .map(document -> template.getConverter().read(GenerationFileDocument.class, document))
                .orElseThrow(() -> new IllegalStateException("parent generation is missing copied source path " + path));
        Document storedArtifact = Optional.ofNullable(template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                .find(new Document("sourceArtifactId", file.sourceArtifactId().value())).first())
                .orElseThrow(() -> new IllegalStateException("parent generation is missing copied source artifact " + path));
        String content = storedArtifact.getString("utf8Content");
        if (Objects.isNull(content)) {
            throw new IllegalStateException("parent generation source artifact content is unavailable " + path);
        }
        SourceArtifactDocument artifact = SourceArtifactDocument.create(content);
        List<SymbolDocument> symbols = template.getCollection(IndexCollections.SYMBOLS).find(scope).map(document ->
                copy(template.getConverter().read(SymbolDocument.class, document), lease, targetRevision)).into(new ArrayList<>());
        List<RelationDocument> relations = template.getCollection(IndexCollections.RELATIONS).find(scope).map(document ->
                copy(mapper.reconstructRelation(document), lease, targetRevision)).into(new ArrayList<>());
        List<EntryPointDocument> entryPoints = template.getCollection(IndexCollections.ENTRY_POINTS).find(scope).map(document ->
                copy(mapper.reconstructEntryPoint(document), lease, targetRevision)).into(new ArrayList<>());
        List<SearchDocument> search = template.getCollection(IndexCollections.SEARCH).find(scope).map(document ->
                copy(mapper.reconstructSearch(document), lease, targetRevision)).into(new ArrayList<>());
        Optional<SourceIndexIssue> issue = file.extractionIssueCode().isEmpty() ? Optional.empty()
                : Optional.of(new SourceIndexIssue(path, file.extractionIssueCode()));
        List<SourceIndexBatch> batches = JdtLsRepositoryIndexExporter.split(lease.repositoryId(), lease.generationId(), path, artifact, issue,
                file.scope(), symbols, relations, entryPoints, search);
        for (SourceIndexBatch batch : batches) {
            writer.writeBatch(lease, "copy:" + batch.batchId(), mapper.map(batch, false, batch.sourceChunk() == 0));
        }
    }

    private static SymbolDocument copy(SymbolDocument parent, MongoGenerationWriter.GenerationLease lease, RepositoryRevision targetRevision) {
        return new SymbolDocument(lease.repositoryId(), lease.generationId(), copy(parent.fact(), lease, targetRevision), parent.kind(),
                parent.owner(), parent.name(), parent.signature(), parent.declaredType(), parent.modifiers(), parent.annotations(),
                parent.sourceArtifactId(), parent.range());
    }

    private static RelationDocument copy(RelationDocument parent, MongoGenerationWriter.GenerationLease lease, RepositoryRevision targetRevision) {
        CodeFactIdentity from = copy(parent.from(), lease, targetRevision);
        com.java.semantic.model.codefact.RelationTarget target = copy(parent.target(), lease, targetRevision);
        com.java.semantic.model.codefact.RelationIdentity canonical = new com.java.semantic.model.codefact.RelationIdentity(from, parent.kind(),
                target, parent.range());
        CodeFactIdentity identity = new CodeFactIdentity(lease.repositoryId(), targetRevision, parent.fact().identity().kind(), canonical);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        return new RelationDocument(lease.repositoryId(), lease.generationId(), fact, parent.kind(), from, target, parent.sourceArtifactId(), parent.range());
    }

    private static EntryPointDocument copy(EntryPointDocument parent, MongoGenerationWriter.GenerationLease lease, RepositoryRevision targetRevision) {
        return new EntryPointDocument(lease.repositoryId(), lease.generationId(), copy(parent.fact(), lease, targetRevision), parent.kind(),
                parent.method(), parent.trigger(), parent.range());
    }

    private static SearchDocument copy(SearchDocument parent, MongoGenerationWriter.GenerationLease lease, RepositoryRevision targetRevision) {
        CodeFactIdentity authority = copy(parent.authoritativeIdentity(), lease, targetRevision);
        return new SearchDocument(lease.repositoryId(), lease.generationId(), CodeFactId.from(authority), parent.kind(), parent.normalizedTokens(),
                parent.packageName(), parent.authoritativeProjection(), authority, parent.scope());
    }

    private static CodeFact copy(CodeFact parent, MongoGenerationWriter.GenerationLease lease, RepositoryRevision targetRevision) {
        CodeFactIdentity identity = copy(parent.identity(), lease, targetRevision);
        return new CodeFact(CodeFactId.from(identity), identity);
    }

    private static CodeFactIdentity copy(CodeFactIdentity parent, MongoGenerationWriter.GenerationLease lease,
                                         RepositoryRevision targetRevision) {
        com.java.semantic.model.codefact.CanonicalIdentity canonical = parent.canonicalIdentity();
        if (canonical instanceof com.java.semantic.model.codefact.RelationIdentity relation) {
            canonical = new com.java.semantic.model.codefact.RelationIdentity(copy(relation.from(), lease, targetRevision), relation.relationKind(),
                    copy(relation.target(), lease, targetRevision), relation.occurrence());
        }
        return new CodeFactIdentity(lease.repositoryId(), targetRevision, parent.kind(), canonical);
    }

    private static com.java.semantic.model.codefact.RelationTarget copy(com.java.semantic.model.codefact.RelationTarget target,
                                                                          MongoGenerationWriter.GenerationLease lease,
                                                                          RepositoryRevision targetRevision) {
        if (target instanceof com.java.semantic.model.codefact.RelationTarget.Internal internal) {
            return new com.java.semantic.model.codefact.RelationTarget.Internal(copy(internal.identity(), lease, targetRevision));
        }
        return target;
    }
}
