package com.java.semantic.query.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/** Latest-only selector. No historical generation parameter is accepted. */
public final class MongoCurrentGenerationReader {
    private final MongoTemplate template;
    public MongoCurrentGenerationReader(MongoTemplate template) { this.template = Objects.requireNonNull(template, "mongo template is required"); }
    public CurrentGeneration read(RepositoryId repositoryId) {
        try {
            Document repository = Optional.ofNullable(template.getCollection("repositories").find(Filters.eq("repoId", repositoryId.value())).first())
                    .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
            return selected(repositoryId, repository);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    /** Only repositories with a valid published pointer are visible. */
    public List<CurrentGeneration> list() {
        try {
            List<CurrentGeneration> result = new ArrayList<>();
            for (Document repository : template.getCollection("repositories").find(Filters.exists("current", true))) {
                try {
                    String value = repository.getString("repoId");
                    if (!StringUtils.hasText(value)) {
                        continue;
                    }
                    result.add(selected(new RepositoryId(value), repository));
                } catch (IndexNotReadyException | IllegalArgumentException | ClassCastException exception) {
                    // A stale or malformed pointer is not a published repository.
                }
            }
            return List.copyOf(result);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private CurrentGeneration selected(RepositoryId repositoryId, Document repository) {
        Document pointer = Optional.ofNullable(repository.get("current", Document.class))
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        String generation = Optional.ofNullable(pointer.getString("generationId"))
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        String digest = Optional.ofNullable(pointer.getString("manifestDigest"))
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        Document manifest = Optional.ofNullable(template.getCollection("generation_manifests").find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("generationId", generation), Filters.eq("identityDigest", digest), Filters.eq("writeState", "SEALED_VALID"))).first())
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        if (!compatibleManifest(manifest)) {
            throw new IndexNotReadyException("INDEX_NOT_READY");
        }
        return new CurrentGeneration(repositoryId, new GenerationId(generation), manifest.getString("identityDigest"));
    }

    private static boolean compatibleManifest(Document manifest) {
        Integer schemaVersion = manifest.getInteger("schemaVersion");
        if (!Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(schemaVersion)) {
            return false;
        }
        List<Document> actualVersions = manifest.getList("projectionVersions", Document.class);
        if (actualVersions == null) { // cs-allow absent compatibility metadata means not ready
            return false;
        }
        java.util.Map<String, Integer> actual = new java.util.HashMap<>();
        for (Document version : actualVersions) {
            String name = version.getString("name");
            Integer value = version.getInteger("version");
            if (name == null || value == null) { // cs-allow malformed metadata is not compatible
                return false;
            }
            actual.put(name, value);
        }
        return IndexSchemaContract.requiredProjectionVersions().equals(actual);
    }
    public record CurrentGeneration(RepositoryId repositoryId, GenerationId generationId, String manifestDigest) { }
}
