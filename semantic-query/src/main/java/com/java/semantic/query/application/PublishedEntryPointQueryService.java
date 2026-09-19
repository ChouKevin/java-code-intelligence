package com.java.semantic.query.application;

import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.PublishedEntryPoint;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.query.SelectedGeneration;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Authorized entry-point and route lookup reader over a selected generation. */
public final class PublishedEntryPointQueryService {
    private static final Pattern HTTP_METHOD = Pattern.compile("^[A-Z]+$");
    private final MongoTemplate template;
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;

    public PublishedEntryPointQueryService(MongoTemplate template, SelectedGenerationGuard guard, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }


    public PublishedEntryPointResult findRoutes(SelectedGeneration context, String repositoryId, String revision, String httpMethod,
                                                String path, int offset, int limit) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        String requestedMethod = requiredHttpMethod(httpMethod);
        String requestedPath = requiredRoutePath(path);
        requirePage(offset, limit);
        if (!selected.repositoryId().value().equals(repositoryId) || !selected.revision().value().equals(revision)) {
            throw new IllegalArgumentException("request repository and revision must match the selected generation");
        }
        guard.require(selected, SelectedGenerationGuard.ENTRY_POINTS);
        SearchAccessPlan accessPlan = guard.searchAccessPlan(repositoryId);
        try {
            org.bson.conversions.Bson filter = accessPlan.authorized(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                    Filters.eq("path", requestedPath), Filters.in("httpMethod", matchingMethods(requestedMethod))));
            long total = template.getCollection(IndexCollections.ENTRY_POINTS).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.ENTRY_POINTS).find(filter)
                    .sort(Sorts.ascending("method", "canonical")).skip(offset).limit(limit)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<PublishedEntryPoint> result = new ArrayList<>();
            for (Document row : rows) {
                EntryPointDocument entryPoint = template.getConverter().read(EntryPointPersistence.class, row).toModel();
                if (!selected.repositoryId().equals(entryPoint.repositoryId()) || !selected.generationId().equals(entryPoint.generationId())
                        || entryPoint.kind() != EntryPointKind.HTTP || !matchingMethods(requestedMethod).contains(entryPoint.trigger().httpMethod().orElse(""))
                        || !requestedPath.equals(entryPoint.trigger().httpPath().orElse(""))
                        || !entryPoint.fact().id().value().equals(required(row, "entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(required(row, "canonical"))) { throw new IndexContractMismatchException(); }
                guard.requireVisible(selected, entryPoint.fact().identity());
                if (!CodeFactScope.from(entryPoint.fact().identity()).equals(flattenedScope(row))) { throw new IndexContractMismatchException(); }
                result.add(new PublishedEntryPoint(selected, entryPoint.fact().id().value(), entryPoint.fact().identity().canonicalForm(),
                        entryPoint.kind(), entryPoint.method().canonicalForm(), entryPoint.trigger().httpPath().orElseThrow(IndexContractMismatchException::new),
                        entryPoint.range().sourceFile()));
            }
            return new PublishedEntryPointResult(selected, result, total, offset + result.size() < total);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException | RepositoryNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }


    public PublishedEntryPointResult listEntryPoints(SelectedGeneration context, String repositoryId, String revision,
                                                      Set<EntryPointKind> kinds, int offset, int limit) {
        SelectedGeneration selectedGeneration = Objects.requireNonNull(context, "selected generation is required");
        Set<EntryPointKind> requestedKinds = Set.copyOf(Objects.requireNonNull(kinds, "entry point kinds are required"));
        requirePage(offset, limit);
        if (!selectedGeneration.repositoryId().value().equals(repositoryId) || !selectedGeneration.revision().value().equals(revision)) {
            throw new IllegalArgumentException("request repository and revision must match the selected generation");
        }
        guard.require(selectedGeneration, SelectedGenerationGuard.ENTRY_POINTS);
        SearchAccessPlan accessPlan = guard.searchAccessPlan(repositoryId);
        try {
            org.bson.conversions.Bson base = Filters.and(Filters.eq("repoId", selectedGeneration.repositoryId().value()),
                    Filters.eq("generationId", selectedGeneration.generationId().value()));
            org.bson.conversions.Bson selected = requestedKinds.isEmpty() ? base : Filters.and(base,
                    Filters.in("entryPoint.kind", requestedKinds.stream().map(Enum::name).sorted().toList()));
            org.bson.conversions.Bson filter = accessPlan.authorized(selected);
            long total = template.getCollection(IndexCollections.ENTRY_POINTS).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.ENTRY_POINTS).find(filter)
                    .sort(Sorts.ascending("entryPoint.kind", "method", "canonical")).skip(offset).limit(limit)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<PublishedEntryPoint> result = new ArrayList<>();
            for (Document row : rows) {
                EntryPointDocument entryPoint = template.getConverter().read(EntryPointPersistence.class, row).toModel();
                if (!selectedGeneration.repositoryId().equals(entryPoint.repositoryId())
                        || !selectedGeneration.generationId().equals(entryPoint.generationId())
                        || (!requestedKinds.isEmpty() && !requestedKinds.contains(entryPoint.kind()))
                        || !entryPoint.fact().id().value().equals(required(row, "entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(required(row, "canonical"))) {
                    throw new IndexContractMismatchException();
                }
                guard.requireVisible(selectedGeneration, entryPoint.fact().identity());
                if (!CodeFactScope.from(entryPoint.fact().identity()).equals(flattenedScope(row))) {
                    throw new IndexContractMismatchException();
                }
                result.add(new PublishedEntryPoint(selectedGeneration, entryPoint.fact().id().value(), entryPoint.fact().identity().canonicalForm(),
                        entryPoint.kind(), entryPoint.method().canonicalForm(), triggerValue(entryPoint),
                        entryPoint.range().sourceFile()));
            }
            return new PublishedEntryPointResult(selectedGeneration, result, total, offset + result.size() < total);
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

    private static List<String> matchingMethods(String requestedMethod) {
        return "ALL".equals(requestedMethod) ? List.of("ALL") : List.of(requestedMethod, "ALL");
    }

    private static String triggerValue(EntryPointDocument entryPoint) {
        return entryPoint.trigger().httpPath()
                .or(() -> entryPoint.trigger().destination().map(destination -> destination.canonicalForm()))
                .or(entryPoint.trigger()::schedule)
                .orElseThrow(IndexContractMismatchException::new);
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > SemanticQueryContract.MAX_LIMIT) {
            throw new IllegalArgumentException("invalid page");
        }
    }

}
