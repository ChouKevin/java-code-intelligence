package com.java.semantic.query.application;

import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.PublishedEntryPoint;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Authorized current-generation entry-point and route lookup reader. */
public final class PublishedEntryPointQueryService {
    private static final Pattern HTTP_METHOD = Pattern.compile("^[A-Z]+$");
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;
    private final Duration storageTimeout;

    public PublishedEntryPointQueryService(MongoTemplate template, CurrentGenerationSelector selector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public List<PublishedEntryPoint> findRoutes(String repositoryId, String revision, String httpMethod, String path) {
        String requestedMethod = requiredHttpMethod(httpMethod);
        String requestedPath = requiredRoutePath(path);
        SearchAccessPlan accessPlan = selector.searchAccessPlan(repositoryId);
        CurrentGeneration current = selector.select(repositoryId, revision, CurrentGenerationSelector.ENTRY_POINTS);
        try {
            FindIterable<Document> rows = template.getCollection(IndexCollections.ENTRY_POINTS).find(accessPlan.authorized(Filters.and(
                    Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                    Filters.eq("path", requestedPath), Filters.eq("httpMethod", requestedMethod)))).sort(Sorts.ascending("method", "canonical"))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<PublishedEntryPoint> result = new ArrayList<>();
            for (Document row : rows) {
                EntryPointDocument entryPoint = template.getConverter().read(EntryPointPersistence.class, row).toModel();
                if (!current.repositoryId().equals(entryPoint.repositoryId()) || !current.generationId().equals(entryPoint.generationId())
                        || entryPoint.kind() != EntryPointKind.HTTP || !requestedMethod.equals(entryPoint.trigger().httpMethod().orElse(""))
                        || !requestedPath.equals(entryPoint.trigger().httpPath().orElse(""))
                        || !entryPoint.fact().id().value().equals(required(row, "entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(required(row, "canonical"))) { throw new IndexContractMismatchException(); }
                selector.requireVisible(current, entryPoint.fact().identity());
                if (!CodeFactScope.from(entryPoint.fact().identity()).equals(flattenedScope(row))) {
                    throw new IndexContractMismatchException();
                }
                result.add(new PublishedEntryPoint(current, entryPoint.fact().id().value(), entryPoint.fact().identity().canonicalForm(),
                        entryPoint.kind(), entryPoint.method().canonicalForm(), entryPoint.trigger().httpPath().orElseThrow(IndexContractMismatchException::new),
                        entryPoint.range().sourceFile()));
            }
            return List.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RepositoryNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    /** Lists only persisted, policy-visible entry points from one exact published revision. */
    public List<PublishedEntryPoint> listEntryPoints(String repositoryId, String revision) {
        SearchAccessPlan accessPlan = selector.searchAccessPlan(repositoryId);
        CurrentGeneration current = selector.select(repositoryId, revision, CurrentGenerationSelector.ENTRY_POINTS);
        try {
            FindIterable<Document> rows = template.getCollection(IndexCollections.ENTRY_POINTS).find(accessPlan.authorized(Filters.and(
                    Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()))))
                    .sort(Sorts.ascending("kind", "method", "canonical")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<PublishedEntryPoint> result = new ArrayList<>();
            for (Document row : rows) {
                EntryPointDocument entryPoint = template.getConverter().read(EntryPointPersistence.class, row).toModel();
                if (!current.repositoryId().equals(entryPoint.repositoryId()) || !current.generationId().equals(entryPoint.generationId())
                        || !entryPoint.fact().id().value().equals(required(row, "entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(required(row, "canonical"))) {
                    throw new IndexContractMismatchException();
                }
                selector.requireVisible(current, entryPoint.fact().identity());
                if (!CodeFactScope.from(entryPoint.fact().identity()).equals(flattenedScope(row))) {
                    throw new IndexContractMismatchException();
                }
                result.add(new PublishedEntryPoint(current, entryPoint.fact().id().value(), entryPoint.fact().identity().canonicalForm(),
                        entryPoint.kind(), entryPoint.method().canonicalForm(), entryPoint.trigger().httpPath().orElse(""),
                        entryPoint.range().sourceFile()));
            }
            return List.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException | RepositoryNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static CodeFactScope flattenedScope(Document document) {
        Object rawParameters = document.get("scopeParameters");
        if (!(rawParameters instanceof List<?> parameters)) {
            throw new IndexContractMismatchException();
        }
        List<String> parameterTypes = new ArrayList<>();
        for (Object parameter : parameters) {
            if (!(parameter instanceof String value) || !StringUtils.hasText(value)) {
                throw new IndexContractMismatchException();
            }
            parameterTypes.add(value);
        }
        return new CodeFactScope(requiredPackage(document), required(document, "scopeClass"),
                Optional.of(required(document, "scopeMethod")), parameterTypes, Optional.of(required(document, "scopePath")));
    }

    private static String requiredPackage(Document document) {
        Object value = document.get("scopePackage");
        if (!(value instanceof String packageName) || (!packageName.isEmpty() && !StringUtils.hasText(packageName))) {
            throw new IndexContractMismatchException();
        }
        return packageName;
    }

    private static String required(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) { throw new IndexContractMismatchException(); }
        return text;
    }

    private static String requiredHttpMethod(String value) {
        if (!StringUtils.hasText(value) || !HTTP_METHOD.matcher(value).matches()) {
            throw new IllegalArgumentException("HTTP method must be an uppercase token");
        }
        return value;
    }

    private static String requiredRoutePath(String value) {
        if (!StringUtils.hasText(value) || !value.startsWith("/") || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("path must be a nonblank absolute route");
        }
        return value;
    }
}
