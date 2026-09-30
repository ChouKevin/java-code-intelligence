package com.java.semantic.query.application;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;
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
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Facade-admission selector for current repository pointers; {@link SelectedGenerationGuard} validates selected-read policy and projections. */
public final class CurrentGenerationSelector {
    private final MongoTemplate template;
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;

    public CurrentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        this.guard = new SelectedGenerationGuard(template, readPolicy, storageTimeout);
    }

    public SelectedGenerationGuard.SourceContext selectSourceContext(String requestedRepositoryId, String requestedRevision,
            ProjectionRequirements requirements) {
        return guard.requireSourceContext(selectedPointer(request(requestedRepositoryId, requestedRevision)), requirements);
    }

    SelectedGenerationGuard.SourceContext publishedContext(RepositoryId repositoryId, Document repository,
            ProjectionRequirements requirements) {
        if (!guard.isRepositoryVisible(repositoryId)) throw new RepositoryNotFoundException();
        return guard.requireSourceContext(pointer(repositoryId, repository), requirements);
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
