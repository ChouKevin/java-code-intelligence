package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;
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
import java.util.ArrayList;
import java.util.Comparator;
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
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;

    public CurrentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        this.guard = new SelectedGenerationGuard(template, readPolicy, storageTimeout);
    }

    public SelectedGeneration selectSource(String requestedRepositoryId, String requestedRevision, SourceTypeIdentity sourceType) {
        return selectSource(requestedRepositoryId, requestedRevision, sourceType, SOURCES);
    }

    /** Selects an authorized source scope while requiring only the caller's actual persisted projections. */
    public SelectedGeneration selectSource(String requestedRepositoryId, String requestedRevision, SourceTypeIdentity sourceType,
                                          ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        SelectedGeneration current = selectedPointer(request);
        guard.requireSourceVisible(current, identity);
        return guard.require(current, requiredRequirements);
    }

    void requireVisible(SelectedGeneration current, CodeFactIdentity codeFact) {
        guard.requireVisible(current, codeFact);
    }

    void requireSourceVisible(SelectedGeneration current, SourceTypeIdentity sourceType) {
        guard.requireSourceVisible(current, sourceType);
    }

    void requireCompatible(SelectedGeneration current, ProjectionRequirements requirements) {
        guard.require(current, requirements);
    }

    public SelectedGeneration selectCodeFact(String requestedRepositoryId, String requestedRevision, CodeFactIdentity codeFact) {
        Request request = request(requestedRepositoryId, requestedRevision);
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        return selectCodeFact(request, identity, requirementsFor(identity));
    }

    /** Selects one authorized generation for a fact query requiring additional projections. */
    public SelectedGeneration selectCodeFact(String requestedRepositoryId, String requestedRevision, CodeFactIdentity codeFact,
                                            ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        return selectCodeFact(request, identity, requiredRequirements);
    }

    private SelectedGeneration selectCodeFact(Request request, CodeFactIdentity identity, ProjectionRequirements requiredRequirements) {
        if (!request.repositoryId().equals(identity.repositoryId()) || !request.revision().equals(identity.repositoryRevision())) {
            throw new IllegalArgumentException("code fact identity repository and revision must match the request");
        }
        SelectedGeneration current = selectedPointer(request);
        guard.requireVisible(current, identity);
        return guard.require(current, requiredRequirements);
    }

    /** Selects an authorized current generation before a bounded projection query. */
    public SelectedGeneration select(String requestedRepositoryId, String requestedRevision,
                                     ProjectionRequirements requirements) {
        Request request = request(requestedRepositoryId, requestedRevision);
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        return guard.require(selectedPointer(request), requiredRequirements);
    }

    public SearchAccessPlan searchAccessPlan(String requestedRepositoryId) {
        return guard.searchAccessPlan(requestedRepositoryId);
    }

    private static ProjectionRequirements requirementsFor(CodeFactIdentity identity) {
        return switch (identity.kind()) {
            case ANNOTATION_USAGE, TYPE_USAGE, SQL_IDENTIFIER, CONFIGURATION_KEY, OUTBOUND_API, MQ_PUBLISHER, ERROR_CONTRACT ->
                    new ProjectionRequirements(EnumSet.of(ProjectionName.RELATIONS, ProjectionName.SYMBOLS));
            case API_ROUTE, MQ_DESTINATION, SCHEDULE -> ENTRY_POINTS;
            default -> SYMBOLS;
        };
    }

    public SelectedGeneration currentRepository(String requestedRepositoryId) {
        return currentPointer(parseAndAuthorize(requestedRepositoryId));
    }

    public List<SelectedGeneration> listCurrentRepositories() {
        try {
            List<SelectedGeneration> result = new ArrayList<>();
            FindIterable<Document> repositories = template.getCollection(IndexCollections.REPOSITORIES).find()
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            for (Document candidate : repositories) {
                Object repositoryValue = candidate.get("repoId");
                if (!(repositoryValue instanceof String value) || !StringUtils.hasText(value)) { continue; }
                try {
                    RepositoryId repositoryId = new RepositoryId(value);
                if (guard.isRepositoryVisible(repositoryId)) {
                    result.add(pointer(repositoryId, candidate));
                }
                } catch (IndexNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
                    // Catalogs omit unpublished or incompatible rows.
                }
            }
            result.sort(Comparator.comparing(current -> current.repositoryId().value()));
            return List.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private Request request(String requestedRepositoryId, String requestedRevision) {
        return new Request(parseAndAuthorize(requestedRepositoryId), new RepositoryRevision(requestedRevision));
    }

    private SelectedGeneration selectedPointer(Request request) {
        SelectedGeneration current = currentPointer(request.repositoryId());
        if (!current.revision().equals(request.revision())) {
            throw new RevisionOutdatedException(request.repositoryId(), request.revision(), current.revision());
        }
        return current;
    }
    private RepositoryId parseAndAuthorize(String requestedRepositoryId) {
        RepositoryId repositoryId = new RepositoryId(requestedRepositoryId);
        if (!guard.isRepositoryVisible(repositoryId)) { throw new RepositoryNotFoundException(); }
        return repositoryId;
    }

    private SelectedGeneration currentPointer(RepositoryId repositoryId) {
        try {
            Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(Filters.eq("repoId", repositoryId.value()))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(repository)) { throw new RepositoryNotFoundException(); }
            return pointer(repositoryId, repository);
        } catch (MongoException | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private SelectedGeneration pointer(RepositoryId repositoryId, Document repository) {
        try {
            Document currentPointer = requiredCurrentPointer(repository);
            String revision = requiredPointerText(currentPointer, "revision");
            String generation = requiredPointerText(currentPointer, "generationId");
            String digest = requiredPointerText(currentPointer, "manifestDigest");
            requiredPointerText(currentPointer, "committedJobId");
            requiredPointerDate(currentPointer, "publishedAt");
            return new SelectedGeneration(repositoryId, new RepositoryRevision(revision), new GenerationId(generation), new ManifestDigest(digest));
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }


    private static String requiredPointerText(Document document, String field) {
        if (!document.containsKey(field)) { throw new IndexNotReadyException(); }
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) { throw new IndexContractMismatchException(); }
        return text;
    }

    private static Document requiredCurrentPointer(Document repository) {
        if (!repository.containsKey("currentPointer")) { throw new IndexNotReadyException(); }
        Object value = repository.get("currentPointer");
        if (!(value instanceof Document pointer)) { throw new IndexContractMismatchException(); }
        return pointer;
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
