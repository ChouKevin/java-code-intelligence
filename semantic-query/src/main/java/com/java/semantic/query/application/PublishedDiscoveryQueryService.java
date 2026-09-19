package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.codefact.DeclarationResolutionQuery;
import com.java.semantic.model.codefact.DeclarationResolutionResult;
import com.java.semantic.model.codefact.EventListenerCandidate;
import com.java.semantic.model.codefact.EventListenerQuery;
import com.java.semantic.model.codefact.EventListenerResult;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.codefact.TypeMemberResult;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SymbolDocument;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Mongo-only declaration and event-listener discovery over selected current symbols. */
public final class PublishedDiscoveryQueryService {
    private static final Set<String> LISTENER_ANNOTATIONS = Set.of("org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener", "EventListener", "TransactionalEventListener");
    private final MongoTemplate template;
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;
    private final SourceIndexCoverageReader coverageReader;

    public PublishedDiscoveryQueryService(MongoTemplate template, SelectedGenerationGuard guard, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        this.coverageReader = new SourceIndexCoverageReader(template, storageTimeout);
    }


    public DeclarationResolutionResult resolveDeclaration(SelectedGeneration context, DeclarationResolutionQuery query) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        DeclarationResolutionQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        requireSourceContext(selected, requiredQuery.repositoryId(), requiredQuery.revision(), requiredQuery.context());
        SearchAccessPlan accessPlan = guard.searchAccessPlan(requiredQuery.repositoryId().value());
        try {
            FindIterable<Document> rows = template.getCollection(IndexCollections.SYMBOLS).find(accessPlan.authorized(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                    Filters.eq("sourcePath", requiredQuery.context().sourceFile()), Filters.eq("name", requiredQuery.symbol()))))
                    .sort(Sorts.ascending("canonical")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<CodeFactSummary> candidates = new ArrayList<>();
            for (Document row : rows) {
                SymbolDocument symbol = CodeFactReadService.decode(row, selected, template);
                if (isSupportedDeclaration(symbol.kind()) && ownsContext(symbol, requiredQuery.context())
                        && containsPosition(symbol, requiredQuery.position())) {
                    guard.requireVisible(selected, symbol.fact().identity());
                    candidates.add(new CodeFactSummary(symbol.fact(), symbol.range()));
                }
            }
            return new DeclarationResolutionResult(selected,
                    candidates.stream().min(Comparator.comparing(candidate -> candidate.fact().id().value())));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }


    public EventListenerResult discoverEventListeners(SelectedGeneration context, EventListenerQuery query) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        EventListenerQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        requireContext(selected, requiredQuery.repositoryId(), requiredQuery.revision(), SelectedGenerationGuard.SYMBOLS);
        SearchAccessPlan accessPlan = guard.searchAccessPlan(requiredQuery.repositoryId().value());
        try {
            org.bson.conversions.Bson filter = accessPlan.authorizedMethod(listenerFilter(selected, requiredQuery));
            long total = template.getCollection(IndexCollections.SYMBOLS).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.SYMBOLS).find(filter)
                    .sort(Sorts.ascending("canonical")).skip(requiredQuery.offset()).limit(requiredQuery.limit())
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<EventListenerCandidate> candidates = new ArrayList<>();
            for (Document row : rows) {
                SymbolDocument symbol = CodeFactReadService.decode(row, selected, template);
                candidates.add(listenerCandidate(row, symbol, selected, requiredQuery));
            }
            return new EventListenerResult(selected, requiredQuery, candidates, total,
                    requiredQuery.offset() + candidates.size() < total);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static org.bson.conversions.Bson listenerFilter(SelectedGeneration current, EventListenerQuery query) {
        return Filters.and(Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                Filters.eq("kind", CodeFactKind.METHOD.name()),
                Filters.eq("fact.identity.canonicalIdentity.parameterTypes", query.eventType()),
                Filters.in("annotations.typeName", LISTENER_ANNOTATIONS));
    }

    private EventListenerCandidate listenerCandidate(Document row, SymbolDocument symbol, SelectedGeneration current, EventListenerQuery query) {
        if (!(symbol.fact().identity().canonicalIdentity() instanceof MethodTarget target)
                || !target.parameterTypes().contains(query.eventType()) || !hasListenerAnnotation(symbol)) {
            throw new IndexContractMismatchException();
        }
        guard.requireVisible(current, symbol.fact().identity());
        if (!CodeFactScope.from(symbol.fact().identity()).equals(flattenedScope(row))) {
            throw new IndexContractMismatchException();
        }
        return new EventListenerCandidate(target, symbol.range(), listenerAnnotations(symbol));
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
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IndexContractMismatchException();
        }
        return text;
    }


    public TypeMemberResult discoverTypeMembers(SelectedGeneration context, TypeMemberQuery query) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        TypeMemberQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        requireSourceContext(selected, requiredQuery.repositoryId(), requiredQuery.revision(), requiredQuery.sourceType());
        SearchAccessPlan accessPlan = guard.searchAccessPlan(requiredQuery.repositoryId().value());
        try {
            List<String> kinds = requiredQuery.kinds().stream().map(Enum::name).sorted().toList();
            org.bson.conversions.Bson filter = accessPlan.authorized(Filters.and(Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("generationId", selected.generationId().value()), Filters.eq("owner", requiredQuery.sourceType().fullyQualifiedName()),
                    Filters.in("kind", kinds)));
            long total = template.getCollection(IndexCollections.SYMBOLS).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.SYMBOLS).find(filter)
                    .sort(Sorts.ascending("kind", "name", "canonical")).skip(requiredQuery.offset()).limit(requiredQuery.limit())
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<CodeFactSummary> members = new ArrayList<>();
            for (Document row : rows) {
                SymbolDocument symbol = CodeFactReadService.decode(row, selected, template);
                if (!requiredQuery.kinds().contains(symbol.kind()) || !requiredQuery.sourceType().fullyQualifiedName().equals(symbol.owner())) {
                    throw new IndexContractMismatchException();
                }
                guard.requireVisible(selected, symbol.fact().identity());
                members.add(new CodeFactSummary(symbol.fact(), symbol.range()));
            }
            boolean hasMore = requiredQuery.offset() + members.size() < total;
            return new TypeMemberResult(selected, requiredQuery, members, total, hasMore,
                    coverageReader.coverage(selected, accessPlan, Optional.empty(), Optional.of(requiredQuery.sourceType().sourceFile())));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void requireSourceContext(SelectedGeneration context, com.java.semantic.model.repository.RepositoryId repositoryId,
                                      com.java.semantic.model.repository.RepositoryRevision revision,
                                      com.java.semantic.model.codefact.SourceTypeIdentity sourceType) {
        guard.requireSourceVisible(context, sourceType);
        requireContext(context, repositoryId, revision, SelectedGenerationGuard.SYMBOLS);
    }

    private void requireContext(SelectedGeneration context, com.java.semantic.model.repository.RepositoryId repositoryId,
                                com.java.semantic.model.repository.RepositoryRevision revision,
                                com.java.semantic.model.index.ProjectionRequirements requirements) {
        if (!context.repositoryId().equals(repositoryId) || !context.revision().equals(revision)) {
            throw new IllegalArgumentException("query repository and revision must match the selected generation");
        }
        guard.require(context, requirements);
    }
    private static boolean isSupportedDeclaration(CodeFactKind kind) {
        return kind == CodeFactKind.TYPE || kind == CodeFactKind.METHOD || kind == CodeFactKind.FIELD
                || kind == CodeFactKind.ENUM_CONSTANT || kind == CodeFactKind.RECORD_COMPONENT || kind == CodeFactKind.MAPPER_STATEMENT;
    }

    private static boolean ownsContext(SymbolDocument symbol, com.java.semantic.model.codefact.SourceTypeIdentity context) {
        return symbol.owner().equals(context.fullyQualifiedName());
    }

    private static boolean containsPosition(SymbolDocument symbol, Optional<com.java.semantic.model.codefact.SyntaxPosition> position) {
        return position.map(value -> symbol.range().range().start().compareTo(value) <= 0
                && value.compareTo(symbol.range().range().end()) < 0).orElse(true);
    }

    private static boolean hasListenerAnnotation(SymbolDocument symbol) {
        return symbol.annotations().stream().anyMatch(annotation -> LISTENER_ANNOTATIONS.contains(annotation.typeName()));
    }

    private static List<com.java.semantic.model.codefact.AnnotationFact> listenerAnnotations(SymbolDocument symbol) {
        return symbol.annotations().stream().filter(annotation -> LISTENER_ANNOTATIONS.contains(annotation.typeName())).toList();
    }
}
