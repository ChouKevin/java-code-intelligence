package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactDisplay;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
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
import com.java.semantic.model.index.persistence.SymbolPersistence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
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
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
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

    public CodeFactDetails get(SourceContext source, CodeFactId id) {
        return getAll(source, Set.of(id)).get(id);
    }

    public Map<CodeFactId, CodeFactDetails> getAll(SourceContext source, Set<CodeFactId> ids) {
        source.requireProjections(new ProjectionRequirements(Set.of(ProjectionName.SEARCH)));
        if (ids.isEmpty()) return Map.of();
        try {
            List<Document> rows = template.getCollection(IndexCollections.SEARCH).find(
                    guard.searchAccessPlan(source.selected().repositoryId().value()).authorized(Filters.and(
                            base(source), Filters.in("factId", ids.stream().map(CodeFactId::value).toList()))))
                    .limit(ids.size()).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).into(new ArrayList<>());
            if (rows.size() != ids.size()) throw new CodeFactNotFoundException();
            return authoritativeAll(source, rows);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    Map<CodeFactId, CodeFactDetails> authoritativeAll(SourceContext source, List<Document> rows) {
        Map<CodeFactId, CodeFactKind> requested = new HashMap<>();
        List<SearchRow> searches = new ArrayList<>(rows.size());
        for (Document row : rows) {
            SearchRow search = searchRow(row, source.selected());
            if (Objects.nonNull(requested.put(search.factId(), search.kind()))) throw new IndexContractMismatchException();
            searches.add(search);
        }
        Map<CodeFactId, CodeFactDetails> result = resolve(source, requested);
        for (SearchRow search : searches) verifySearchRow(search, result.get(search.factId()), source.selected());
        return result;
    }

    public Map<CodeFactIdentity, CodeFactDetails> getAllByIdentity(SourceContext source, Set<CodeFactIdentity> identities) {
        Map<CodeFactId, CodeFactKind> requested = new HashMap<>();
        for (CodeFactIdentity identity : identities) {
            if (!source.selected().repositoryId().equals(identity.repositoryId())
                    || !source.selected().revision().equals(identity.repositoryRevision())) throw new IndexContractMismatchException();
            guard.requireVisible(source.selected(), identity);
            requested.put(CodeFactId.from(identity), identity.kind());
        }
        Map<CodeFactId, CodeFactDetails> resolved = resolve(source, requested);
        Map<CodeFactIdentity, CodeFactDetails> result = new HashMap<>();
        for (CodeFactIdentity identity : identities) {
            CodeFactDetails details = resolved.get(CodeFactId.from(identity));
            if (!identity.equals(details.fact().identity())) throw new IndexContractMismatchException();
            result.put(identity, details);
        }
        return Map.copyOf(result);
    }

    private Map<CodeFactId, CodeFactDetails> resolve(SourceContext source, Map<CodeFactId, CodeFactKind> requested) {
        Map<CodeFactId, CodeFactDetails> result = new HashMap<>();
        List<StoredFact> stored = new ArrayList<>();
        try {
            for (ProjectionName authority : List.of(ProjectionName.SYMBOLS, ProjectionName.RELATIONS, ProjectionName.ENTRY_POINTS)) {
                List<String> ids = requested.entrySet().stream().filter(entry -> authorityFor(entry.getValue()) == authority)
                        .map(entry -> entry.getKey().value()).toList();
                if (ids.isEmpty()) continue;
                source.requireProjections(new ProjectionRequirements(Set.of(authority)));
                String collection = collection(authority);
                String key = authorityKey(authority);
                for (Document row : template.getCollection(collection).find(Filters.and(base(source), Filters.in(key, ids)))
                        .limit(ids.size()).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    StoredFact fact = storedFact(source, authority, row);
                    if (!requested.containsKey(fact.details().fact().id())
                            || requested.get(fact.details().fact().id()) != fact.details().fact().identity().kind()
                            || Objects.nonNull(result.put(fact.details().fact().id(), fact.details()))) throw new IndexContractMismatchException();
                    guard.requireVisible(source.selected(), fact.details().fact().identity());
                    stored.add(fact);
                }
            }
            if (result.size() != requested.size()) throw new CodeFactNotFoundException();
            validateSources(source, stored);
            return Map.copyOf(result);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    StoredFact storedFact(SourceContext source, ProjectionName authority, Document row) {
        SelectedGeneration selected = source.selected();
        return switch (authority) {
            case SYMBOLS -> {
                SymbolDocument symbol = decode(row, selected, template);
                verifyScope(row, symbol.fact().identity());
                yield new StoredFact(new CodeFactDetails(selected, symbol.fact(), symbol.range(), symbol.annotations(),
                        symbol.mapperStatementKind()), Optional.of(symbol.sourceArtifactId()));
            }
            case RELATIONS -> {
                RelationDocument relation = decodeRelation(row, selected, template);
                yield new StoredFact(new CodeFactDetails(selected, relation.fact(), relation.range(), List.of(), Optional.empty()),
                        Optional.of(relation.sourceArtifactId()));
            }
            case ENTRY_POINTS -> storedEntryPoint(source, row, decodeEntryPoint(row, selected, template));
            default -> throw new IndexContractMismatchException();
        };
    }

    StoredFact storedEntryPoint(SourceContext source, Document row, EntryPointDocument entry) {
        verifyScope(row, entry.fact().identity());
        if (!entry.method().canonicalForm().equals(requiredText(row, "method"))
                || !entry.trigger().httpMethod().orElse("").equals(requiredString(row, "httpMethod"))
                || !entry.trigger().httpPath().orElse("").equals(requiredString(row, "path"))) throw new IndexContractMismatchException();
        return new StoredFact(new CodeFactDetails(source.selected(), entry.fact(), entry.range(), List.of(), Optional.empty()), Optional.empty());
    }

    void validateSources(SourceContext source, List<StoredFact> facts) {
        Set<String> paths = new HashSet<>();
        for (StoredFact fact : facts) paths.add(fact.details().location().sourceFile());
        Map<String, GenerationFileDocument> files = sourceFiles(source, paths);
        for (StoredFact fact : facts) {
            GenerationFileDocument file = files.get(fact.details().location().sourceFile());
            if (fact.artifact().filter(artifact -> !file.sourceArtifactId().equals(artifact)).isPresent()) throw new IndexContractMismatchException();
        }
    }

    Map<String, GenerationFileDocument> sourceFiles(SourceContext source, Set<String> paths) {
        source.requireProjections(new ProjectionRequirements(Set.of(ProjectionName.SOURCES)));
        if (paths.isEmpty()) return Map.of();
        Map<String, GenerationFileDocument> files = new HashMap<>();
        try {
            for (Document row : template.getCollection(IndexCollections.GENERATION_FILES)
                    .find(Filters.and(base(source), Filters.in("sourcePath", paths))).limit(paths.size())
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                Document converted = new Document(row);
                converted.put("generationId", new Document("value", source.selected().generationId().value()));
                GenerationFileDocument file;
                try { file = template.getConverter().read(GenerationFileDocument.class, converted); }
                catch (RuntimeException exception) { throw new IndexContractMismatchException(); }
                if (!source.selected().repositoryId().equals(file.repositoryId())
                        || !source.selected().generationId().equals(file.generationId())
                        || !file.sourcePath().equals(requiredText(row, "sourcePath"))
                        || !paths.contains(file.sourcePath()) || !source.policy().allowsCode(file.sourcePath())
                        || !file.sourceArtifactId().value().equals(file.contentHash())
                        || Objects.nonNull(files.put(file.sourcePath(), file))) throw new IndexContractMismatchException();
            }
            if (files.size() != paths.size()) throw new CodeFactNotFoundException();
            Set<String> found = new HashSet<>();
            for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(
                    Filters.eq("repoId", source.selected().repositoryId().value()),
                    Filters.eq("snapshotId", source.snapshot().snapshotId().value()), Filters.in("path", paths)))
                    .projection(Projections.include("repoId", "snapshotId", "path", "mode", "contentKind", "contentStatus",
                            "policyFingerprint", "checksum")).limit(paths.size())
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                String path = requiredText(row, "path");
                GenerationFileDocument file = files.get(path);
                if (Objects.isNull(file) || !found.add(path) || !"CODE".equals(row.getString("contentKind"))
                        || !"TEXT".equals(row.getString("contentStatus"))
                        || !List.of("100644", "100755").contains(row.getString("mode"))
                        || !source.fingerprint().equals(row.getString("policyFingerprint"))
                        || !file.contentHash().equals(row.getString("checksum"))) throw new IndexContractMismatchException();
            }
            if (found.size() != paths.size()) throw new IndexContractMismatchException();
            return Map.copyOf(files);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    public void requireWholeSourceVisible(SourceContext source, String path) {
        source.requireProjections(new ProjectionRequirements(Set.of(ProjectionName.SYMBOLS, ProjectionName.SOURCES)));
        SourceArtifactId artifact = sourceFiles(source, Set.of(path)).get(path).sourceArtifactId();
        String after = "";
        try {
            Bson wholeSource = guard.searchAccessPlan(source.selected().repositoryId().value()).authorizedSource(
                    Filters.and(base(source), Filters.eq("sourcePath", path)));
            if (Objects.isNull(template.getCollection(IndexCollections.GENERATION_FILES).find(wholeSource)
                    .projection(Projections.include("sourcePath")).limit(1)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first())) throw new RepositoryNotFoundException();
            while (true) {
                List<Document> rows = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(base(source),
                        Filters.eq("sourcePath", path), Filters.gt("symbolId", after))).sort(Sorts.ascending("symbolId"))
                        .limit(100).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).into(new ArrayList<>());
                if (rows.isEmpty()) return;
                for (Document row : rows) {
                    StoredFact fact = storedFact(source, ProjectionName.SYMBOLS, row);
                    guard.requireVisible(source.selected(), fact.details().fact().identity());
                    if (!artifact.equals(fact.artifact().orElseThrow(IndexContractMismatchException::new))) throw new IndexContractMismatchException();
                    after = requiredText(row, "symbolId");
                }
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    static ProjectionRequirements requirementsForSearchKinds(Set<CodeFactKind> kinds) {
        EnumSet<ProjectionName> projections = EnumSet.of(ProjectionName.SEARCH, ProjectionName.SOURCES);
        for (CodeFactKind kind : kinds.isEmpty() ? EnumSet.allOf(CodeFactKind.class) : kinds) projections.add(authorityFor(kind));
        return new ProjectionRequirements(projections);
    }

    private static Bson base(SourceContext source) {
        return Filters.and(Filters.eq("repoId", source.selected().repositoryId().value()),
                Filters.eq("generationId", source.selected().generationId().value()));
    }
    private static String collection(ProjectionName authority) {
        return switch (authority) {
            case SYMBOLS -> IndexCollections.SYMBOLS;
            case RELATIONS -> IndexCollections.RELATIONS;
            case ENTRY_POINTS -> IndexCollections.ENTRY_POINTS;
            default -> throw new IndexContractMismatchException();
        };
    }
    private static String authorityKey(ProjectionName authority) {
        return switch (authority) {
            case SYMBOLS -> "symbolId";
            case RELATIONS -> "relationId";
            case ENTRY_POINTS -> "entryPointId";
            default -> throw new IndexContractMismatchException();
        };
    }
    static void verifyScope(Document row, CodeFactIdentity identity) {
        CodeFactScope scope = new CodeFactScope(requiredString(row, "scopePackage"), requiredText(row, "scopeClass"),
                optionalText(row, "scopeMethod"), requiredTextList(row, "scopeParameters"), optionalText(row, "scopePath"));
        if (!scope.equals(CodeFactScope.from(identity))) throw new IndexContractMismatchException();
    }
    record StoredFact(CodeFactDetails details, Optional<SourceArtifactId> artifact) { }

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
                    requiredText(stored, "displayName"), requiredString(stored, "signature"), scope, requiredText(stored, "sourcePath"));
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
                || !row.sourcePath().equals(details.location().sourceFile())
                || row.authority() != authorityFor(fact.identity().kind())) { throw new IndexContractMismatchException(); }
    }

    static SymbolDocument decode(Document stored, SelectedGeneration current, MongoTemplate template) {
        try {
            Document converted = new Document(stored);
            converted.put("generationId", new Document("value", current.generationId().value()));
            SymbolDocument symbol = template.getConverter().read(SymbolPersistence.class, converted).toModel();
            CodeFact fact = symbol.fact();
            if (!current.repositoryId().equals(symbol.repositoryId()) || !current.generationId().equals(symbol.generationId())
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.id().value().equals(requiredText(stored, "symbolId"))
                    || !fact.identity().canonicalForm().equals(requiredText(stored, "canonical"))
                    || !symbol.name().equals(CodeFactDisplay.displayName(fact.identity().canonicalIdentity()))
                    || !symbol.owner().equals(symbolOwner(fact.identity()))
                    || !CodeFactScope.from(fact.identity()).sourcePath().equals(Optional.of(symbol.range().sourceFile()))
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
                    || !entryPoint.method().sourceFile().equals(entryPoint.range().sourceFile())
                    || !entryPoint.range().sourceFile().equals(requiredText(stored, "sourcePath"))) { throw new IndexContractMismatchException(); }
            return entryPoint;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static String symbolOwner(CodeFactIdentity identity) {
        return switch (identity.canonicalIdentity()) {
            case com.java.semantic.model.codefact.SourceTypeIdentity type -> type.fullyQualifiedName();
            case com.java.semantic.model.codefact.MethodTarget method -> method.fullyQualifiedClassName();
            case com.java.semantic.model.codefact.MemberIdentity member -> member.owner().fullyQualifiedName();
            case com.java.semantic.model.codefact.MapperStatementIdentity mapper -> mapper.namespace();
            default -> throw new IndexContractMismatchException();
        };
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
            String displayName, String signature, CodeFactScope scope, String sourcePath) { }

}
