package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.query.PublishedRelationPage;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import com.java.semantic.model.repository.RepositoryId;
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
        return find(query, EnumSet.of(RelationKind.REFERENCES));
    }

    public PublishedRelationResult findImplementations(PublishedRelationQuery query) {
        return find(query, EnumSet.of(RelationKind.IMPLEMENTS, RelationKind.OVERRIDES));
    }

    private PublishedRelationResult find(PublishedRelationQuery query, EnumSet<RelationKind> kinds) {
        PublishedRelationQuery request = Objects.requireNonNull(query, "relation query is required");
        CurrentGeneration current = selectAndValidateTarget(request);
        List<RelationDocument> visible = readRelations(current, request.target(), kinds);
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

    private List<RelationDocument> readRelations(CurrentGeneration current, CodeFactIdentity target, EnumSet<RelationKind> kinds) {
        try {
            Bson filter = Filters.and(Filters.eq("repoId", current.repositoryId().value()),
                    Filters.eq("generationId", current.generationId().value()),
                    Filters.eq("target", new RelationTarget.Internal(target).canonicalForm()),
                    Filters.in("kind", kinds.stream().map(Enum::name).toList()));
            FindIterable<Document> rows = template.getCollection(IndexCollections.RELATIONS).find(filter)
                    .sort(Sorts.ascending("from", "target", "kind", "sourcePath", "relationId"))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<RelationDocument> relations = new ArrayList<>();
            for (Document row : rows) {
                RelationDocument relation = decode(row);
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

    RelationDocument decode(Document row) {
        try {
            CodeFact fact = template.getConverter().read(CodeFact.class, row.get("fact", Document.class));
            SourceArtifactId artifactId = template.getConverter().read(SourceArtifactId.class, row.get("sourceArtifactId", Document.class));
            SourceRange range = template.getConverter().read(SourceRange.class, row.get("range", Document.class));
            if (!(fact.identity().canonicalIdentity() instanceof RelationIdentity identity)) {
                throw new PublishedRelationIntegrityException("stored relation fact has no relation identity");
            }
            return new RelationDocument(new RepositoryId(row.getString("repoId")), new GenerationId(row.getString("generationId")), fact,
                    RelationKind.valueOf(row.getString("kind")), identity.from(), identity.target(), artifactId, range);
        } catch (PublishedRelationIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PublishedRelationIntegrityException("stored relation cannot be decoded: " + exception.getClass().getSimpleName());
        }
    }

    void ensureSymbol(CurrentGeneration current, CodeFactIdentity identity) {
        try {
            Document symbol = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()),
                            Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("canonical", identity.canonicalForm())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(symbol)) {
                throw new PublishedRelationIntegrityException("stored internal relation target has no generation symbol");
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
