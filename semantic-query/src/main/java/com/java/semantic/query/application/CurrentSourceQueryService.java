package com.java.semantic.query.application;

import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
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
        CurrentGeneration current = selector.selectSource(repositoryId, revision, sourceType);
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        try {
            requireAuthorizedCurrentSource(current, identity);
            Document mapping = template.getCollection(IndexCollections.GENERATION_FILES).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("sourcePath", identity.sourceFile()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(mapping)) { throw new IndexNotReadyException(); }
            GenerationFileDocument generationFile = decodeGenerationFile(mapping, current, identity.sourceFile());
            String artifactId = generationFile.sourceArtifactId().value();
            Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS).find(Filters.eq("sourceArtifactId", artifactId))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(artifact)) { throw new IndexNotReadyException(); }
            return new PublishedSource(current, identity.sourceFile(), artifactContent(artifact, new SourceArtifactId(artifactId)));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    PublishedSource getSource(CurrentGeneration current, String sourcePath) {
        CurrentGeneration selected = Objects.requireNonNull(current, "current generation is required");
        String path = com.java.semantic.model.support.ModelValidation.repositoryRelativePath(sourcePath);
        try {
            Document mapping = template.getCollection(IndexCollections.GENERATION_FILES).find(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                    Filters.eq("sourcePath", path))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(mapping)) { throw new IndexNotReadyException(); }
            GenerationFileDocument generationFile = decodeGenerationFile(mapping, selected, path);
            Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS).find(Filters.eq("sourceArtifactId", generationFile.sourceArtifactId().value()))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(artifact)) { throw new IndexNotReadyException(); }
            return new PublishedSource(selected, path, artifactContent(artifact, generationFile.sourceArtifactId()));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void requireAuthorizedCurrentSource(CurrentGeneration current, SourceTypeIdentity identity) {
        CodeFactIdentity expectedIdentity = new CodeFactIdentity(current.repositoryId(), current.revision(), CodeFactKind.TYPE, identity);
        CodeFactId expectedId = CodeFactId.from(expectedIdentity);
        boolean foundRequestedType = false;
        try {
            for (Document stored : template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                    Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                    Filters.eq("sourcePath", identity.sourceFile()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                SymbolDocument symbol = decodeSymbol(stored, current);
                if (expectedId.equals(symbol.fact().id()) && expectedIdentity.equals(symbol.fact().identity())
                        && CodeFactKind.TYPE == symbol.kind() && expectedIdentity.canonicalForm().equals(requiredText(stored, "canonical"))) {
                    foundRequestedType = true;
                }
                selector.requireVisible(current, symbol.fact().identity());
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
        if (!foundRequestedType) { throw new IndexNotReadyException(); }
    }

    private SymbolDocument decodeSymbol(Document stored, CurrentGeneration current) {
        try {
            Document converterDocument = new Document(stored);
            converterDocument.put("generationId", new Document("value", current.generationId().value()));
            SymbolDocument decoded = template.getConverter().read(SymbolDocument.class, converterDocument);
            CodeFact fact = decoded.fact();
            if (!current.repositoryId().equals(decoded.repositoryId()) || !current.generationId().equals(decoded.generationId())
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.identity().canonicalForm().equals(requiredText(stored, "canonical"))
                    || !fact.id().value().equals(requiredText(stored, "symbolId")) || !decoded.range().sourceFile().equals(requiredText(stored, "sourcePath"))) {
                throw new IndexContractMismatchException();
            }
            return decoded;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private GenerationFileDocument decodeGenerationFile(Document stored, CurrentGeneration current, String sourcePath) {
        try {
            Document converterDocument = new Document(stored);
            // Generation scope is deliberately flattened by the immutable writer; restore its value-object shape for decoding.
            converterDocument.put("generationId", new Document("value", current.generationId().value()));
            GenerationFileDocument decoded = template.getConverter().read(GenerationFileDocument.class, converterDocument);
            if (!current.repositoryId().equals(decoded.repositoryId()) || !current.generationId().equals(decoded.generationId())
                    || !sourcePath.equals(decoded.sourcePath())) { throw new IndexContractMismatchException(); }
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
                || !expectedId.value().equals(storedId) || !expectedId.value().equals(storedHash)) {
            throw new IndexContractMismatchException();
        }
        return utf8Content;
    }

    private static String requiredText(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) { throw new IndexContractMismatchException(); }
        return text;
    }
}
