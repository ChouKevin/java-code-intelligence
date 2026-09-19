package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Validates policy and projection compatibility for a generation selected at admission. */
public final class SelectedGenerationGuard {
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public SelectedGenerationGuard(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public SelectedGeneration require(SelectedGeneration context, ProjectionRequirements requirements) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        try {
            Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                            Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                            Filters.eq("sourceRevision", selected.revision().value()), Filters.eq("identityDigest", selected.manifestDigest().value()),
                            Filters.eq("writeState", "SEALED_VALID"))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (!isCompatible(manifest, requiredRequirements)) {
                throw new IndexContractMismatchException();
            }
            return selected;
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SearchAccessPlan searchAccessPlan(String requestedRepositoryId) {
        return readPolicy.searchAccessPlan(new com.java.semantic.model.repository.RepositoryId(requestedRepositoryId));
    }

    public void requireSourceVisible(SelectedGeneration context, SourceTypeIdentity sourceType) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        if (!readPolicy.isSourceVisible(selected.repositoryId(), identity)) {
            throw new RepositoryNotFoundException();
        }
    }

    public void requireVisible(SelectedGeneration context, CodeFactIdentity codeFact) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        if (!readPolicy.isCodeFactVisible(selected.repositoryId(), identity)) {
            throw new RepositoryNotFoundException();
        }
    }

    boolean isRepositoryVisible(com.java.semantic.model.repository.RepositoryId repositoryId) {
        return readPolicy.isRepositoryVisible(repositoryId);
    }

    private static boolean isCompatible(Document manifest, ProjectionRequirements requirements) {
        if (Objects.isNull(manifest) || !Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(manifest.get("schemaVersion"))) {
            return false;
        }
        Object storedVersions = manifest.get("projectionVersions");
        if (!(storedVersions instanceof List<?> versions)) {
            return false;
        }
        Map<String, Integer> actual = new HashMap<>();
        for (Object value : versions) {
            if (!(value instanceof Document version)) {
                return false;
            }
            Object name = version.get("name");
            Object number = version.get("version");
            if (!(name instanceof String projectionName) || !StringUtils.hasText(projectionName) || !(number instanceof Integer projectionVersion)) {
                return false;
            }
            actual.put(projectionName, projectionVersion);
        }
        for (ProjectionName required : requirements.names()) {
            if (!Objects.equals(IndexSchemaContract.requiredProjectionVersions().get(required.name()), actual.get(required.name()))) {
                return false;
            }
        }
        return true;
    }
}
