package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
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
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** The sole policy boundary for every generation-backed query read. */
public final class CurrentGenerationSelector {
    public static final ProjectionRequirements SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS));
    public static final ProjectionRequirements SYMBOLS = new ProjectionRequirements(EnumSet.of(ProjectionName.SYMBOLS));
    public static final ProjectionRequirements RELATIONS = new ProjectionRequirements(EnumSet.of(ProjectionName.RELATIONS, ProjectionName.SYMBOLS));
    public static final ProjectionRequirements ENTRY_POINTS = new ProjectionRequirements(EnumSet.of(ProjectionName.ENTRY_POINTS, ProjectionName.SYMBOLS));
    public static final ProjectionRequirements SEARCH = new ProjectionRequirements(EnumSet.of(ProjectionName.SEARCH, ProjectionName.SYMBOLS));
    public static final ProjectionRequirements SEARCH_WITH_SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SEARCH,
            ProjectionName.SOURCES, ProjectionName.SYMBOLS));
    public static final ProjectionRequirements ALL_PROJECTIONS = new ProjectionRequirements(EnumSet.allOf(ProjectionName.class));
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public CurrentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public CurrentGeneration selectSource(String requestedRepositoryId, String requestedRevision, SourceTypeIdentity sourceType) {
        return selectSource(requestedRepositoryId, requestedRevision, sourceType, SOURCES);
    }

    /** Selects an authorized source scope while requiring only the caller's actual persisted projections. */
    public CurrentGeneration selectSource(String requestedRepositoryId, String requestedRevision, SourceTypeIdentity sourceType,
                                          ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        if (!readPolicy.isSourceVisible(request.repositoryId(), identity)) { throw new RepositoryNotFoundException(); }
        CurrentGeneration current = selectedPointer(request);
        verifyManifest(current, requiredRequirements);
        return current;
    }

    void requireVisible(CurrentGeneration current, CodeFactIdentity codeFact) {
        Objects.requireNonNull(current, "current generation is required");
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        if (!readPolicy.isCodeFactVisible(current.repositoryId(), identity)) { throw new RepositoryNotFoundException(); }
    }

    public CurrentGeneration selectCodeFact(String requestedRepositoryId, String requestedRevision, CodeFactIdentity codeFact) {
        Request request = request(requestedRepositoryId, requestedRevision);
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        return selectCodeFact(request, identity, requirementsFor(identity));
    }

    /** Selects one authorized generation for a fact query requiring additional projections. */
    public CurrentGeneration selectCodeFact(String requestedRepositoryId, String requestedRevision, CodeFactIdentity codeFact,
                                            ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        return selectCodeFact(request, identity, requiredRequirements);
    }

    private CurrentGeneration selectCodeFact(Request request, CodeFactIdentity identity, ProjectionRequirements requiredRequirements) {
        if (!request.repositoryId().equals(identity.repositoryId()) || !request.revision().equals(identity.repositoryRevision())) {
            throw new IllegalArgumentException("code fact identity repository and revision must match the request");
        }
        if (!readPolicy.isCodeFactVisible(request.repositoryId(), identity)) { throw new RepositoryNotFoundException(); }
        CurrentGeneration current = selectedPointer(request);
        verifyManifest(current, requiredRequirements);
        return current;
    }

    /** Selects an authorized current generation before a bounded projection query. */
    public CurrentGeneration select(String requestedRepositoryId, String requestedRevision,
                                    ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        CurrentGeneration current = selectedPointer(request);
        verifyManifest(current, requiredRequirements);
        return current;
    }

    public SearchAccessPlan searchAccessPlan(String requestedRepositoryId) {
        return readPolicy.searchAccessPlan(new RepositoryId(requestedRepositoryId));
    }

    private static ProjectionRequirements requirementsFor(CodeFactIdentity identity) {
        return switch (identity.kind()) {
            case ANNOTATION_USAGE, TYPE_USAGE, SQL_IDENTIFIER, CONFIGURATION_KEY, OUTBOUND_API, MQ_PUBLISHER, ERROR_CONTRACT ->
                    new ProjectionRequirements(EnumSet.of(ProjectionName.RELATIONS, ProjectionName.SYMBOLS));
            case API_ROUTE, MQ_DESTINATION, SCHEDULE -> ENTRY_POINTS;
            default -> SYMBOLS;
        };
    }

    public CurrentGeneration currentRepository(String requestedRepositoryId) {
        return currentPointer(parseAndAuthorize(requestedRepositoryId));
    }

    public List<CurrentGeneration> listCurrentRepositories() {
        try {
            List<CurrentGeneration> result = new ArrayList<>();
            FindIterable<Document> repositories = template.getCollection(IndexCollections.REPOSITORIES).find()
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            for (Document candidate : repositories) {
                Object repositoryValue = candidate.get("repoId");
                if (!(repositoryValue instanceof String value) || !StringUtils.hasText(value)) { continue; }
                try {
                    RepositoryId repositoryId = new RepositoryId(value);
                    if (readPolicy.isRepositoryVisible(repositoryId)) {
                    result.add(pointer(repositoryId, candidate));
                    }
                } catch (IndexNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
                    // Catalogs omit unpublished or incompatible rows.
                }
            }
            return List.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private Request request(String requestedRepositoryId, String requestedRevision) {
        return new Request(parseAndAuthorize(requestedRepositoryId), new RepositoryRevision(requestedRevision));
    }

    private CurrentGeneration selectedPointer(Request request) {
        CurrentGeneration current = currentPointer(request.repositoryId());
        if (!current.revision().equals(request.revision())) {
            throw new RevisionOutdatedException(request.repositoryId(), request.revision(), current.revision());
        }
        return current;
    }

    private RepositoryId parseAndAuthorize(String requestedRepositoryId) {
        RepositoryId repositoryId = new RepositoryId(requestedRepositoryId);
        if (!readPolicy.isRepositoryVisible(repositoryId)) { throw new RepositoryNotFoundException(); }
        return repositoryId;
    }

    private CurrentGeneration currentPointer(RepositoryId repositoryId) {
        try {
            Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(Filters.eq("repoId", repositoryId.value()))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(repository)) { throw new RepositoryNotFoundException(); }
            return pointer(repositoryId, repository);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private CurrentGeneration pointer(RepositoryId repositoryId, Document repository) {
        try {
            String revision = requiredPointerText(repository, "revision");
            String generation = requiredPointerText(repository, "generationId");
            String digest = requiredPointerText(repository, "manifestDigest");
            requiredPointerText(repository, "committedJobId");
            Date publishedAt = requiredPointerDate(repository, "publishedAt");
            return new CurrentGeneration(repositoryId, new RepositoryRevision(revision), new GenerationId(generation),
                    new ManifestDigest(digest), Instant.ofEpochMilli(publishedAt.getTime()));
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void verifyManifest(CurrentGeneration current, ProjectionRequirements requirements) {
        Objects.requireNonNull(requirements, "projection requirements are required");
        try {
            Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("sourceRevision", current.revision().value()), Filters.eq("identityDigest", current.manifestDigest().value()),
                            Filters.eq("writeState", "SEALED_VALID"))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (!isCompatible(manifest, requirements)) { throw new IndexContractMismatchException(); }
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static boolean isCompatible(Document manifest, ProjectionRequirements requirements) {
        if (Objects.isNull(manifest) || !Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(manifest.get("schemaVersion"))) { return false; }
        Object storedVersions = manifest.get("projectionVersions");
        if (!(storedVersions instanceof List<?> versions)) { return false; }
        Map<String, Integer> actual = new java.util.HashMap<>();
        for (Object value : versions) {
            if (!(value instanceof Document version)) { return false; }
            Object name = version.get("name");
            Object number = version.get("version");
            if (!(name instanceof String projectionName) || !StringUtils.hasText(projectionName) || !(number instanceof Integer projectionVersion)) { return false; }
            actual.put(projectionName, projectionVersion);
        }
        for (ProjectionName required : requirements.names()) {
            if (!Objects.equals(IndexSchemaContract.requiredProjectionVersions().get(required.name()), actual.get(required.name()))) { return false; }
        }
        return true;
    }

    private static String requiredPointerText(Document document, String field) {
        if (!document.containsKey(field)) { throw new IndexNotReadyException(); }
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) { throw new IndexContractMismatchException(); }
        return text;
    }

    private static Date requiredPointerDate(Document document, String field) {
        if (!document.containsKey(field)) { throw new IndexNotReadyException(); }
        Object value = document.get(field);
        if (!(value instanceof Date date)) { throw new IndexContractMismatchException(); }
        return date;
    }

    private static SemanticIndexUnavailableException unavailable(RuntimeException exception) { return new SemanticIndexUnavailableException(exception); }
    private record Request(RepositoryId repositoryId, RepositoryRevision revision) { }
}
