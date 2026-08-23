package com.java.semantic.query.store;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Objects;
import java.util.Optional;

public final class MongoSourceReader extends MongoProjectionReader {
    public MongoSourceReader(MongoTemplate template) { super(template); }
    public Document read(RepositoryId repositoryId, RepositoryRevision expectedRevision, String normalizedPath) {
        Objects.requireNonNull(normalizedPath, "normalized source path is required");
        try {
            com.java.semantic.model.query.CurrentGeneration generation = new MongoCurrentGenerationReader(template).read(repositoryId, expectedRevision);
            Document mapping = Optional.ofNullable(template.getCollection("generation_files").find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("generationId", generation.generationId().value()), Filters.eq("sourcePath", normalizedPath))).first())
                    .orElseThrow(() -> new IndexNotReadyException("source is not in current generation"));
            String artifactId = Optional.ofNullable(mapping.getString("sourceArtifactId")).orElseThrow(() -> new IndexNotReadyException("source artifact is missing"));
            return Optional.ofNullable(template.getCollection("source_artifacts").find(Filters.eq("sourceArtifactId", artifactId)).first())
                    .orElseThrow(() -> new IndexNotReadyException("source artifact is missing"));
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
