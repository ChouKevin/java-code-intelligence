package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactDisplay;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.persistence.EntryPointPersistence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Resolves derived search rows to their one authoritative selected-generation fact. */
public final class CodeFactReadService {
    private final MongoTemplate template;
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;

    public CodeFactReadService(MongoTemplate template, SelectedGenerationGuard guard, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }


    public CodeFactDetails get(SelectedGeneration context, CodeFactReadQuery query) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        CodeFactReadQuery request = Objects.requireNonNull(query, "code fact read query is required");
        if (!selected.repositoryId().equals(request.repositoryId()) || !selected.revision().equals(request.revision())) {
            throw new IllegalArgumentException("query repository and revision must match the selected generation");
        }
        SearchAccessPlan accessPlan = guard.searchAccessPlan(request.repositoryId().value());
        guard.require(selected, new ProjectionRequirements(EnumSet.of(ProjectionName.SEARCH)));
        try {
            Bson filter = accessPlan.authorized(Filters.and(Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("generationId", selected.generationId().value()), Filters.eq("factId", request.factId().value())));
            Document row = template.getCollection(IndexCollections.SEARCH).find(filter)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row)) { throw new CodeFactNotFoundException(); }
            SearchRow search = searchRow(row, selected);
            guard.require(selected, requirementsForSearchKinds(Set.of(search.kind())));
            CodeFactDetails details = authoritative(selected, search.kind(), search.factId());
            verifySearchRow(search, details, selected);
            guard.requireVisible(selected, details.fact().identity());
            return details;
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | CodeFactNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    /** Internal exact-identity helper for source slices that have not yet been handed a search hit. */
    CodeFactDetails get(SelectedGeneration context, CodeFactIdentity identity) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        CodeFactIdentity expectedIdentity = Objects.requireNonNull(identity, "code fact identity is required");
        guard.requireVisible(selected, expectedIdentity);
        guard.require(selected, requirementsForSearchKinds(Set.of(expectedIdentity.kind())));
        try {
            CodeFactDetails details = authoritative(selected, expectedIdentity.kind(), CodeFactId.from(expectedIdentity));
            if (!expectedIdentity.equals(details.fact().identity())) { throw new IndexContractMismatchException(); }
            return details;
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (CodeFactNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    CodeFactDetails authoritative(Document row, SelectedGeneration current) {
        SelectedGeneration selected = Objects.requireNonNull(current, "selected generation is required");
        try {
            SearchRow search = searchRow(row, selected);
            CodeFactDetails details = authoritative(selected, search.kind(), search.factId());
            verifySearchRow(search, details, selected);
            return details;
        } catch (CodeFactNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    static ProjectionRequirements requirementsForSearchKinds(Set<CodeFactKind> requestedKinds) {
        Set<CodeFactKind> kinds = Objects.requireNonNull(requestedKinds, "requested kinds are required");
        EnumSet<ProjectionName> projections = EnumSet.of(ProjectionName.SEARCH);
        Set<CodeFactKind> effectiveKinds = kinds.isEmpty() ? EnumSet.allOf(CodeFactKind.class) : kinds;
        for (CodeFactKind kind : effectiveKinds) {
            projections.add(authorityFor(kind));
        }
        return new ProjectionRequirements(projections);
    }

    private CodeFactDetails authoritative(SelectedGeneration current, CodeFactKind kind, CodeFactId id) {
        return switch (authorityFor(kind)) {
            case SYMBOLS -> symbol(current, id);
            case RELATIONS -> relation(current, id);
            case ENTRY_POINTS -> entryPoint(current, id);
            default -> throw new IndexContractMismatchException();
        };
    }

    private CodeFactDetails symbol(SelectedGeneration current, CodeFactId id) {
        Document document = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                Filters.eq("symbolId", id.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(document)) { throw new CodeFactNotFoundException(); }
        SymbolDocument symbol = decode(document, current, template);
        if (!id.equals(symbol.fact().id())) { throw new IndexContractMismatchException(); }
        return new CodeFactDetails(current, symbol.fact(), symbol.range(), symbol.annotations());
    }

    private CodeFactDetails relation(SelectedGeneration current, CodeFactId id) {
        Document document = template.getCollection(IndexCollections.RELATIONS).find(Filters.and(
                Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                Filters.eq("relationId", id.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(document)) { throw new CodeFactNotFoundException(); }
        RelationDocument relation = decodeRelation(document, current, template);
        if (!id.equals(relation.fact().id())) { throw new IndexContractMismatchException(); }
        return new CodeFactDetails(current, relation.fact(), relation.range(), List.of());
    }

    private CodeFactDetails entryPoint(SelectedGeneration current, CodeFactId id) {
        Document document = template.getCollection(IndexCollections.ENTRY_POINTS).find(Filters.and(
                Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                Filters.eq("entryPointId", id.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(document)) { throw new CodeFactNotFoundException(); }
        EntryPointDocument entryPoint = decodeEntryPoint(document, current, template);
        if (!id.equals(entryPoint.fact().id())) { throw new IndexContractMismatchException(); }
        return new CodeFactDetails(current, entryPoint.fact(), entryPoint.range(), List.of());
    }

    private static SearchRow searchRow(Document row, SelectedGeneration current) {
        Document stored = Objects.requireNonNull(row, "search row is required");
        if (!current.repositoryId().value().equals(requiredText(stored, "repoId"))
                || !current.generationId().value().equals(requiredText(stored, "generationId"))) { throw new IndexContractMismatchException(); }
        try {
            CodeFactId factId = new CodeFactId(requiredText(stored, "factId"));
            CodeFactKind kind = CodeFactKind.valueOf(requiredText(stored, "kind"));
            ProjectionName authority = ProjectionName.valueOf(requiredText(stored, "authority"));
            CodeFactScope scope = new CodeFactScope(requiredString(stored, "scopePackage"), requiredText(stored, "scopeClass"),
                    optionalText(stored, "scopeMethod"), requiredTextList(stored, "scopeParameters"), optionalText(stored, "scopePath"));
            if (!authorityFor(kind).equals(authority)) { throw new IndexContractMismatchException(); }
            return new SearchRow(factId, kind, authority, requiredText(stored, "canonical"),
                    requiredText(stored, "displayName"), requiredString(stored, "signature"), scope);
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static void verifySearchRow(SearchRow row, CodeFactDetails details, SelectedGeneration current) {
        CodeFact fact = details.fact();
        if (!current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                || !row.factId().equals(fact.id()) || row.kind() != fact.identity().kind()
                || !row.canonical().equals(fact.identity().canonicalForm()) || !row.scope().equals(CodeFactScope.from(fact.identity()))
                || !row.displayName().equals(CodeFactDisplay.displayName(fact.identity().canonicalIdentity()))
                || !row.signature().equals(CodeFactDisplay.signature(fact.identity().canonicalIdentity()))
                || row.authority() != authorityFor(fact.identity().kind())) { throw new IndexContractMismatchException(); }
    }

    static SymbolDocument decode(Document stored, SelectedGeneration current, MongoTemplate template) {
        try {
            Document converted = new Document(stored);
            converted.put("generationId", new Document("value", current.generationId().value()));
            SymbolDocument symbol = template.getConverter().read(SymbolDocument.class, converted);
            CodeFact fact = symbol.fact();
            if (!current.repositoryId().equals(symbol.repositoryId()) || !current.generationId().equals(symbol.generationId())
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.id().value().equals(requiredText(stored, "symbolId"))
                    || !fact.identity().canonicalForm().equals(requiredText(stored, "canonical"))
                    || !symbol.range().sourceFile().equals(requiredText(stored, "sourcePath"))) { throw new IndexContractMismatchException(); }
            return symbol;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    static RelationDocument decodeRelation(Document stored, SelectedGeneration current, MongoTemplate template) {
        try {
            CodeFact fact = template.getConverter().read(CodeFact.class, stored.get("fact", Document.class));
            SourceArtifactId artifactId = template.getConverter().read(SourceArtifactId.class, stored.get("sourceArtifactId", Document.class));
            SourceRange range = template.getConverter().read(SourceRange.class, stored.get("range", Document.class));
            if (!(fact.identity().canonicalIdentity() instanceof RelationIdentity identity)) { throw new IndexContractMismatchException(); }
            RelationDocument relation = new RelationDocument(new RepositoryId(requiredText(stored, "repoId")),
                    new GenerationId(requiredText(stored, "generationId")), fact, identity.relationKind(), identity.from(), identity.target(), artifactId, range);
            if (!current.repositoryId().equals(relation.repositoryId()) || !current.generationId().equals(relation.generationId())
                    || !current.repositoryId().value().equals(requiredText(stored, "repoId"))
                    || !current.generationId().value().equals(requiredText(stored, "generationId"))
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.id().value().equals(requiredText(stored, "relationId"))
                    || !fact.identity().canonicalForm().equals(requiredText(stored, "canonical"))
                    || !relation.from().canonicalForm().equals(requiredText(stored, "from"))
                    || !relation.target().canonicalForm().equals(requiredText(stored, "target"))
                    || !relation.kind().name().equals(requiredText(stored, "kind"))
                    || !relation.range().sourceFile().equals(requiredText(stored, "sourcePath"))) { throw new IndexContractMismatchException(); }
            return relation;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    static EntryPointDocument decodeEntryPoint(Document stored, SelectedGeneration current, MongoTemplate template) {
        try {
            EntryPointPersistence persisted = template.getConverter().read(EntryPointPersistence.class, stored);
            EntryPointDocument entryPoint = persisted.toModel();
            CodeFact fact = entryPoint.fact();
            if (!current.repositoryId().equals(entryPoint.repositoryId()) || !current.generationId().equals(entryPoint.generationId())
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.id().value().equals(requiredText(stored, "entryPointId"))
                    || !fact.identity().canonicalForm().equals(requiredText(stored, "canonical"))
                    || !entryPoint.range().sourceFile().equals(requiredText(stored, "sourcePath"))) { throw new IndexContractMismatchException(); }
            return entryPoint;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static ProjectionName authorityFor(CodeFactKind kind) {
        return switch (kind) {
            case API_ROUTE, MQ_DESTINATION, SCHEDULE -> ProjectionName.ENTRY_POINTS;
            case ANNOTATION_USAGE, TYPE_USAGE, SQL_IDENTIFIER, CONFIGURATION_KEY, OUTBOUND_API, MQ_PUBLISHER, ERROR_CONTRACT -> ProjectionName.RELATIONS;
            default -> ProjectionName.SYMBOLS;
        };
    }

    static String requiredText(Document document, String field) {
        String value = requiredString(document, field);
        if (!StringUtils.hasText(value)) { throw new IndexContractMismatchException(); }
        return value;
    }

    private static String requiredString(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text)) { throw new IndexContractMismatchException(); }
        return text;
    }

    private static Optional<String> optionalText(Document document, String field) {
        String value = requiredString(document, field);
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }

    private static List<String> requiredTextList(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) { throw new IndexContractMismatchException(); }
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text)) { throw new IndexContractMismatchException(); }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private record SearchRow(CodeFactId factId, CodeFactKind kind, ProjectionName authority, String canonical,
            String displayName, String signature, CodeFactScope scope) { }

}
