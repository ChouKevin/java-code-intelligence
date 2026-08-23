package com.java.semantic.query.store;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Objects;

abstract class MongoProjectionReader {
    final MongoTemplate template;
    private final MongoCurrentGenerationReader current;
    MongoProjectionReader(MongoTemplate template) { this.template = Objects.requireNonNull(template, "mongo template is required"); current = new MongoCurrentGenerationReader(template); }
    final List<Document> selected(String collection, RepositoryId repositoryId, RepositoryRevision expectedRevision) {
        try {
            com.java.semantic.model.query.CurrentGeneration generation = current.read(repositoryId, expectedRevision);
            return template.getCollection(collection).find(Filters.and(Filters.eq("repoId", repositoryId.value()), Filters.eq("generationId", generation.generationId().value()))).into(new java.util.ArrayList<>());
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
