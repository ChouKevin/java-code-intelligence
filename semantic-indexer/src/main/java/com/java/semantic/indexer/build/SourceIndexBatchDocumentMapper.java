package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.MongoGenerationWriter.StoredDocument;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.repository.RepositoryId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoConverter;

/** Maps one bounded source batch to the immutable Task 5 storage collections. */
public final class SourceIndexBatchDocumentMapper {
    private final MongoConverter converter;

    public SourceIndexBatchDocumentMapper(MongoConverter converter) {
        this.converter = Objects.requireNonNull(converter, "mongo converter is required");
    }

    public List<StoredDocument> map(SourceIndexBatch batch) {
        return map(batch, true);
    }

    public List<StoredDocument> map(SourceIndexBatch batch, boolean includeSourceArtifact) {
        return map(batch, includeSourceArtifact, batch.sourceChunk() == 0);
    }

    public List<StoredDocument> map(SourceIndexBatch batch, boolean includeSourceArtifact, boolean includeGenerationFile) {
        Objects.requireNonNull(batch, "source batch is required");
        List<StoredDocument> documents = new ArrayList<>();
        if (includeSourceArtifact) {
            documents.add(stored(IndexCollections.SOURCE_ARTIFACTS, batch.sourceArtifact(), document -> {
                document.put("sourceArtifactId", batch.sourceArtifact().id().value());
                document.put("contentHash", batch.sourceArtifact().contentHash());
            }));
        }
        if (includeGenerationFile) {
            GenerationFileDocument generationFile = new GenerationFileDocument(batch.repositoryId(), batch.generationId(), batch.sourcePath(),
                    batch.sourceArtifact().id(), batch.sourceArtifact().contentHash(),
                    batch.extractionIssue().map(com.java.semantic.model.index.SourceIndexIssue::code).orElse(""), batch.sourceScope());
            documents.add(stored(IndexCollections.GENERATION_FILES, generationFile, document -> {
                document.put("sourcePath", batch.sourcePath());
                document.put("extractionIssueCode", batch.extractionIssue().map(com.java.semantic.model.index.SourceIndexIssue::code).orElse(""));
                document.put("scopeUsable", batch.sourceScope().usableScopes());
                document.put("scopePackages", batch.sourceScope().packages());
                document.put("scopeClassKeys", batch.sourceScope().classKeys());
                document.put("scopeMethodKeys", batch.sourceScope().methodKeys());
            }));
        }
        batch.symbols().forEach(symbol -> documents.add(stored(IndexCollections.SYMBOLS, symbol, document -> {
            CodeFactScope scope = CodeFactScope.from(symbol.fact().identity());
            document.put("symbolId", symbol.fact().id().value());
            document.put("canonical", symbol.fact().identity().canonicalForm());
            document.put("sourcePath", batch.sourcePath());
            document.put("scopePackage", scope.packageName());
            document.put("scopeClass", scope.className());
            document.put("scopeMethod", scope.methodName().orElse(""));
            document.put("scopeParameters", scope.parameterTypes());
            document.put("scopePath", scope.sourcePath().orElse(""));
        })));
        batch.relations().forEach(relation -> documents.add(stored(IndexCollections.RELATIONS, relation, document -> {
            document.put("relationId", relation.fact().id().value());
            document.put("canonical", relation.fact().identity().canonicalForm());
            document.put("from", relation.from().canonicalForm());
            document.put("target", relation.target().canonicalForm());
            document.put("sourcePath", batch.sourcePath());
        })));
        batch.entryPoints().forEach(entryPoint -> documents.add(stored(IndexCollections.ENTRY_POINTS,
                EntryPointPersistence.from(entryPoint), document -> {
            CodeFactScope scope = CodeFactScope.from(entryPoint.fact().identity());
            document.put("entryPointId", entryPoint.fact().id().value());
            document.put("canonical", entryPoint.fact().identity().canonicalForm());
            document.put("sourcePath", batch.sourcePath());
            document.put("method", entryPoint.method().canonicalForm());
            entryPoint.trigger().httpPath().ifPresent(path -> document.put("path", path));
            entryPoint.trigger().httpMethod().ifPresent(method -> document.put("httpMethod", method));
            document.put("scopePackage", scope.packageName());
            document.put("scopeClass", scope.className());
            document.put("scopeMethod", scope.methodName().orElse(""));
            document.put("scopeParameters", scope.parameterTypes());
            document.put("scopePath", scope.sourcePath().orElse(""));
        })));
        batch.search().forEach(search -> documents.add(stored(IndexCollections.SEARCH,
                IndexProjectionPersistence.SearchPersistence.from(search), document -> {
            document.put("factId", search.factId().value());
            document.put("kind", search.kind().name());
            document.put("tokens", search.normalizedTokens());
            document.put("package", search.packageName().orElse(""));
            document.put("authority", search.authoritativeProjection().name());
            document.put("canonical", search.authoritativeIdentity().canonicalForm());
            document.put("scopePackage", search.scope().packageName());
            document.put("scopeClass", search.scope().className());
            document.put("scopeMethod", search.scope().methodName().orElse(""));
            document.put("scopeParameters", search.scope().parameterTypes());
            document.put("scopePath", search.scope().sourcePath().orElse(""));
            document.put("sourcePath", batch.sourcePath());
        })));
        return List.copyOf(documents);
    }

    private StoredDocument stored(String collection, Object value, java.util.function.Consumer<Document> enrich) {
        Document document = new Document();
        converter.write(value, document);
        enrich.accept(document);
        return new StoredDocument(collection, document);
    }

    com.java.semantic.model.index.EntryPointDocument reconstructEntryPoint(Document document) {
        EntryPointPersistence persistence = converter.read(EntryPointPersistence.class, document);
        return persistence.toModel();
    }

    RelationDocument reconstructRelation(Document document) {
        CodeFact fact = converter.read(CodeFact.class, document.get("fact", Document.class));
        SourceArtifactId sourceArtifactId = converter.read(SourceArtifactId.class,
                document.get("sourceArtifactId", Document.class));
        SourceRange range = converter.read(SourceRange.class, document.get("range", Document.class));
        if (!(fact.identity().canonicalIdentity() instanceof RelationIdentity identity)) {
            throw new IllegalArgumentException("relation fact has no relation identity");
        }
        return new RelationDocument(new RepositoryId(document.getString("repoId")), new GenerationId(document.getString("generationId")),
                fact, RelationKind.valueOf(document.getString("kind")), identity.from(), identity.target(), sourceArtifactId, range);
    }

    com.java.semantic.model.index.SearchDocument reconstructSearch(Document document) {
        IndexProjectionPersistence.SearchPersistence persistence = converter.read(
                IndexProjectionPersistence.SearchPersistence.class, document);
        return persistence.toModel();
    }
}
