package com.java.semantic.query.application;

import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.query.CurrentGeneration;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class CurrentSourceQueryService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;
    private final Duration storageTimeout;

    public CurrentSourceQueryService(MongoTemplate template, CurrentGenerationSelector selector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public PublishedSource getSource(String repositoryId, String revision, SourceTypeIdentity sourceType) {
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        CurrentGeneration current = selector.selectSource(repositoryId, revision, identity);
        try {
            Document mapping = template.getCollection(IndexCollections.GENERATION_FILES).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("sourcePath", identity.sourceFile()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(mapping)) { throw new IndexNotReadyException(); }
            GenerationFileDocument generationFile = decodeGenerationFile(mapping, current, identity);
            String artifactId = generationFile.sourceArtifactId().value();
            Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS).find(Filters.eq("sourceArtifactId", artifactId))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(artifact)) { throw new IndexNotReadyException(); }
            return new PublishedSource(current, identity.sourceFile(), artifactContent(artifact, new SourceArtifactId(artifactId)));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private GenerationFileDocument decodeGenerationFile(Document stored, CurrentGeneration current, SourceTypeIdentity identity) {
        try {
            Document converterDocument = new Document(stored);
            // Generation scope is deliberately flattened by the immutable writer; restore its value-object shape for decoding.
            converterDocument.put("generationId", new Document("value", current.generationId().value()));
            GenerationFileDocument decoded = template.getConverter().read(GenerationFileDocument.class, converterDocument);
            if (!current.repositoryId().equals(decoded.repositoryId()) || !current.generationId().equals(decoded.generationId())
                    || !identity.sourceFile().equals(decoded.sourcePath())) { throw new IndexContractMismatchException(); }
            return decoded;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static String artifactContent(Document artifact, SourceArtifactId expectedId) {
        Object artifactId = artifact.get("sourceArtifactId");
        Object contentHash = artifact.get("contentHash");
        Object content = artifact.get("utf8Content");
        if (!(artifactId instanceof String storedId) || !(contentHash instanceof String storedHash) || !(content instanceof String utf8Content)
                || !expectedId.value().equals(storedId) || !expectedId.value().equals(storedHash) || !StringUtils.hasText(utf8Content)) {
            throw new IndexContractMismatchException();
        }
        return utf8Content;
    }
}
