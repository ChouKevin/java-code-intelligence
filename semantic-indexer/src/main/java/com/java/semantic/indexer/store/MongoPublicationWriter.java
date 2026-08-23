package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Objects;
import java.util.Optional;

/** Minimal Task 3 test seam; publication compare-and-set belongs to Task 4. */
public final class MongoPublicationWriter {
    private final MongoTemplate template;
    public MongoPublicationWriter(MongoTemplate template) { this.template = Objects.requireNonNull(template, "mongo template is required"); }
    public void setValidatedCurrent(RepositoryId repositoryId, GenerationId generationId) {
        try {
            Optional<Document> manifest = Optional.ofNullable(template.getCollection("generation_manifests").find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("generationId", generationId.value()), Filters.eq("writeState", "SEALED_VALID"))).first());
            if (manifest.isEmpty()) { throw new IllegalStateException("only sealed generation may be current"); }
            String digest = manifest.orElseThrow().getString("identityDigest");
            template.getCollection("repositories").updateOne(Filters.eq("repoId", repositoryId.value()),
                    Updates.set("current", new Document("generationId", generationId.value()).append("manifestDigest", digest)));
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
