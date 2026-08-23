package com.java.semantic.query.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Latest-only selector. Projection reads must pin their repository revision. */
public final class MongoCurrentGenerationReader {

    private final MongoTemplate template;

    public MongoCurrentGenerationReader(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public CurrentGeneration read(RepositoryId repositoryId, RepositoryRevision expectedRevision) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        Objects.requireNonNull(expectedRevision, "expected revision is required");
        CurrentGeneration current = readCurrent(repositoryId);
        if (!current.revision().equals(expectedRevision)) {
            throw new RevisionOutdatedException(expectedRevision, current);
        }
        return current;
    }

    /** Catalog reads can report the current pointer without pinning a client to an old revision. */
    public List<CurrentGeneration> list() {
        try {
            List<CurrentGeneration> result = new ArrayList<>();
            for (Document repository : template.getCollection(IndexCollections.REPOSITORIES).find(Filters.exists("revision", true))) {
                try {
                    String value = repository.getString("repoId");
                    if (!StringUtils.hasText(value)) {
                        continue;
                    }
                    result.add(selected(new RepositoryId(value), repository));
                } catch (IndexNotReadyException | IllegalArgumentException | ClassCastException exception) {
                    // A malformed coordinator row is not a published repository.
                }
            }
            return List.copyOf(result);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private CurrentGeneration readCurrent(RepositoryId repositoryId) {
        try {
            Document repository = Optional.ofNullable(template.getCollection(IndexCollections.REPOSITORIES)
                            .find(Filters.eq("repoId", repositoryId.value())).first())
                    .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
            return selected(repositoryId, repository);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private CurrentGeneration selected(RepositoryId repositoryId, Document repository) {
        String revision = requiredText(repository, "revision");
        String generation = requiredText(repository, "generationId");
        String digest = requiredText(repository, "manifestDigest");
        requiredText(repository, "committedJobId");
        Date publishedAt = Optional.ofNullable(repository.getDate("publishedAt"))
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        RepositoryRevision repositoryRevision = new RepositoryRevision(revision);
        GenerationId generationId = new GenerationId(generation);
        ManifestDigest manifestDigest = new ManifestDigest(digest);
        Document manifest = Optional.ofNullable(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                        Filters.eq("repoId", repositoryId.value()), Filters.eq("generationId", generationId.value()),
                        Filters.eq("sourceRevision", repositoryRevision.value()),
                        Filters.eq("identityDigest", manifestDigest.value()), Filters.eq("writeState", "SEALED_VALID"))).first())
                .orElseThrow(() -> new IndexNotReadyException("INDEX_NOT_READY"));
        if (!compatibleManifest(manifest)) {
            throw new IndexNotReadyException("INDEX_NOT_READY");
        }
        return new CurrentGeneration(repositoryId, repositoryRevision, generationId, manifestDigest,
                Instant.ofEpochMilli(publishedAt.getTime()));
    }

    private static String requiredText(Document document, String field) {
        String value = document.getString(field);
        if (!StringUtils.hasText(value)) {
            throw new IndexNotReadyException("INDEX_NOT_READY");
        }
        return value;
    }

    private static boolean compatibleManifest(Document manifest) {
        Integer schemaVersion = manifest.getInteger("schemaVersion");
        if (!Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(schemaVersion)) {
            return false;
        }
        List<Document> actualVersions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(actualVersions) || actualVersions.isEmpty()) {
            return false;
        }
        Map<String, Integer> actual = new java.util.HashMap<>();
        for (Document version : actualVersions) {
            String name = version.getString("name");
            Integer value = version.getInteger("version");
            if (Objects.isNull(name) || Objects.isNull(value)) {
                return false;
            }
            actual.put(name, value);
        }
        return IndexSchemaContract.requiredProjectionVersions().equals(Map.copyOf(actual));
    }
}
