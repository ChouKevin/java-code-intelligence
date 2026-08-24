package com.java.semantic.query.application;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The sole policy boundary for every generation-backed Query read. */
public final class CurrentGenerationSelector {

    public static final ProjectionRequirements SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES));
    public static final ProjectionRequirements SYMBOLS = new ProjectionRequirements(EnumSet.of(ProjectionName.SYMBOLS));
    public static final ProjectionRequirements ALL_PROJECTIONS = new ProjectionRequirements(EnumSet.allOf(ProjectionName.class));

    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public CurrentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public CurrentGeneration select(String requestedRepositoryId, String requestedRevision, ProjectionRequirements requirements) {
        RepositoryId repositoryId = parseAndAuthorize(requestedRepositoryId);
        RepositoryRevision revision = new RepositoryRevision(requestedRevision);
        CurrentGeneration current = currentPointer(repositoryId);
        if (!current.revision().equals(revision)) {
            throw new RevisionOutdatedException(revision, current.revision());
        }
        verifyManifest(current, requirements);
        return current;
    }

    public CurrentGeneration currentRepository(String requestedRepositoryId) {
        return currentPointer(parseAndAuthorize(requestedRepositoryId));
    }

    public List<CurrentGeneration> listCurrentRepositories() {
        try {
            List<CurrentGeneration> result = new java.util.ArrayList<>();
            for (Document candidate : template.getCollection(IndexCollections.REPOSITORIES).find()
                    .projection(new Document("repoId", 1)).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                String repositoryValue = candidate.getString("repoId");
                if (!StringUtils.hasText(repositoryValue)) {
                    continue;
                }
                try {
                    RepositoryId repositoryId = new RepositoryId(repositoryValue);
                    if (readPolicy.isRepositoryVisible(repositoryId)) {
                        result.add(currentPointer(repositoryId));
                    }
                } catch (IndexNotReadyException | IllegalArgumentException exception) {
                    // Unpublished or malformed coordinator rows are not catalog entries.
                }
            }
            return List.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private RepositoryId parseAndAuthorize(String requestedRepositoryId) {
        RepositoryId repositoryId = new RepositoryId(requestedRepositoryId);
        if (!readPolicy.isRepositoryVisible(repositoryId)) {
            throw new RepositoryNotFoundException();
        }
        return repositoryId;
    }

    private CurrentGeneration currentPointer(RepositoryId repositoryId) {
        try {
            Document repository = Optional.ofNullable(template.getCollection(IndexCollections.REPOSITORIES)
                            .find(Filters.eq("repoId", repositoryId.value())).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first())
                    .orElseThrow(IndexNotReadyException::new);
            return pointer(repositoryId, repository);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private CurrentGeneration pointer(RepositoryId repositoryId, Document repository) {
        String revision = requiredText(repository, "revision");
        String generation = requiredText(repository, "generationId");
        String digest = requiredText(repository, "manifestDigest");
        requiredText(repository, "committedJobId");
        Date publishedAt = Optional.ofNullable(repository.getDate("publishedAt")).orElseThrow(IndexNotReadyException::new);
        return new CurrentGeneration(repositoryId, new RepositoryRevision(revision), new GenerationId(generation),
                new ManifestDigest(digest), Instant.ofEpochMilli(publishedAt.getTime()));
    }

    private void verifyManifest(CurrentGeneration current, ProjectionRequirements requirements) {
        Objects.requireNonNull(requirements, "projection requirements are required");
        try {
            Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()),
                            Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("sourceRevision", current.revision().value()),
                            Filters.eq("identityDigest", current.manifestDigest().value()),
                            Filters.eq("writeState", "SEALED_VALID")))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (!isCompatible(manifest, requirements)) {
                throw new IndexContractMismatchException();
            }
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private static boolean isCompatible(Document manifest, ProjectionRequirements requirements) {
        if (Objects.isNull(manifest) || !Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(manifest.getInteger("schemaVersion"))) {
            return false;
        }
        List<Document> versions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(versions)) {
            return false;
        }
        Map<String, Integer> actual = new java.util.HashMap<>();
        for (Document version : versions) {
            String name = version.getString("name");
            Integer number = version.getInteger("version");
            if (!StringUtils.hasText(name) || Objects.isNull(number)) {
                return false;
            }
            actual.put(name, number);
        }
        for (ProjectionName required : requirements.names()) {
            Integer expectedVersion = IndexSchemaContract.requiredProjectionVersions().get(required.name());
            if (!Objects.equals(expectedVersion, actual.get(required.name()))) {
                return false;
            }
        }
        return true;
    }

    private static String requiredText(Document document, String field) {
        String value = document.getString(field);
        if (!StringUtils.hasText(value)) {
            throw new IndexNotReadyException();
        }
        return value;
    }

    private static SemanticIndexUnavailableException unavailable(RuntimeException exception) {
        return new SemanticIndexUnavailableException(exception);
    }
}
