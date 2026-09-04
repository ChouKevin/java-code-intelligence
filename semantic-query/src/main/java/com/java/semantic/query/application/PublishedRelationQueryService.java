package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.query.PublishedRelationPage;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Query boundary for published relation facts. */
public final class PublishedRelationQueryService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector generationSelector;
    private final Duration storageTimeout;

    public PublishedRelationQueryService(MongoTemplate template, CurrentGenerationSelector generationSelector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.generationSelector = Objects.requireNonNull(generationSelector, "generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public PublishedRelationResult findReferences(PublishedRelationQuery query) {
        return findByTarget(query, EnumSet.of(RelationKind.REFERENCES));
    }

    public PublishedRelationResult findImplementations(PublishedRelationQuery query) {
        return findByTarget(query, EnumSet.of(RelationKind.IMPLEMENTS, RelationKind.OVERRIDES));
    }

    public PublishedRelationResult findCallers(PublishedRelationQuery query) {
        return findByTarget(query, EnumSet.of(RelationKind.CALLS));
    }

    public PublishedRelationResult findCallees(PublishedRelationQuery query) {
        return findBySource(query, EnumSet.of(RelationKind.CALLS));
    }

    private PublishedRelationResult findByTarget(PublishedRelationQuery query, EnumSet<RelationKind> kinds) {
        PublishedRelationQuery request = Objects.requireNonNull(query, "relation query is required");
        return find(request, kinds, "target", new RelationTarget.Internal(request.target()).canonicalForm());
    }

    private PublishedRelationResult findBySource(PublishedRelationQuery query, EnumSet<RelationKind> kinds) {
        PublishedRelationQuery request = Objects.requireNonNull(query, "relation query is required");
        return find(request, kinds, "from", request.target().canonicalForm());
    }

    private PublishedRelationResult find(PublishedRelationQuery query, EnumSet<RelationKind> kinds, String endpointField,
                                         String endpointValue) {
        PublishedRelationQuery request = Objects.requireNonNull(query, "relation query is required");
        CurrentGeneration current = selectAndValidateTarget(request);
        List<RelationDocument> visible = readRelations(current, kinds, endpointField, endpointValue);
        int start = Math.min(request.offset(), visible.size());
        int end = Math.min(start + request.limit(), visible.size());
        List<RelationDocument> page = visible.subList(start, end);
        return new PublishedRelationResult(current, request.target(), page,
                new PublishedRelationPage(request.offset(), request.limit(), page.size(), visible.size()));
    }

    private CurrentGeneration selectAndValidateTarget(PublishedRelationQuery query) {
        CurrentGeneration current = generationSelector.selectCodeFact(query.repositoryId().value(), query.revision().value(), query.target(),
                CurrentGenerationSelector.RELATIONS);
        ensureSymbol(current, query.target());
        return current;
    }

    private List<RelationDocument> readRelations(CurrentGeneration current, EnumSet<RelationKind> kinds, String endpointField,
                                                 String endpointValue) {
        try {
            Bson filter = Filters.and(Filters.eq("repoId", current.repositoryId().value()),
                    Filters.eq("generationId", current.generationId().value()),
                    Filters.eq(endpointField, endpointValue),
                    Filters.in("kind", kinds.stream().map(Enum::name).toList()));
            FindIterable<Document> rows = template.getCollection(IndexCollections.RELATIONS).find(filter)
                    .sort(Sorts.ascending("kind", "from", "sourcePath", "relationId"))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<RelationDocument> relations = new ArrayList<>();
            for (Document row : rows) {
                RelationDocument relation = decode(row, current);
                if (isVisible(current, relation)) {
                    relations.add(relation);
                }
            }
            return relations.stream().sorted(RELATION_ORDER).toList();
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    static final Comparator<RelationDocument> RELATION_ORDER = Comparator
            .comparing((RelationDocument relation) -> relation.from().canonicalForm())
            .thenComparing(relation -> relation.target().canonicalForm())
            .thenComparing(relation -> relation.kind().name())
            .thenComparing(relation -> relation.range().sourceFile())
            .thenComparingInt(relation -> relation.range().range().start().line())
            .thenComparingInt(relation -> relation.range().range().start().character())
            .thenComparing(relation -> relation.fact().id().value());

    RelationDocument decode(Document row, CurrentGeneration current) {
        return CodeFactReadService.decodeRelation(row, current, template);
    }

    void ensureSymbol(CurrentGeneration current, CodeFactIdentity identity) {
        try {
            Document symbol = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()),
                            Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("canonical", identity.canonicalForm())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(symbol)) {
                throw new IndexContractMismatchException();
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    boolean isVisible(CurrentGeneration current, RelationDocument relation) {
        try {
            generationSelector.requireVisible(current, relation.from());
            if (relation.target() instanceof RelationTarget.Internal internalTarget) {
                generationSelector.requireVisible(current, internalTarget.identity());
                ensureSymbol(current, internalTarget.identity());
            }
            ensureSymbol(current, relation.from());
            return true;
        } catch (RepositoryNotFoundException exception) {
            return false;
        }
    }
}
