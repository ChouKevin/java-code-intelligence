package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactDisplay;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactTokenizer;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.query.application.ReadContextSelector.AdmittedContext;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.java.semantic.query.config.SearchAccessPlan;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import static com.java.semantic.query.application.SemanticQueryContract.*;

/** Four bounded metadata-only semantic operations over one already admitted context. */
public final class SelectedSemanticQueryService {
    private static final int BATCH = 100;
    private static final List<String> LISTENERS = List.of("org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener", "EventListener", "TransactionalEventListener");
    private final MongoTemplate template;
    private final SelectedGenerationGuard guard;
    private final Duration timeout;
    private final CodeFactReadService facts;

    public SelectedSemanticQueryService(MongoTemplate template, SelectedGenerationGuard guard, Duration timeout,
            CodeFactReadService facts) {
        this.template = Objects.requireNonNull(template);
        this.guard = Objects.requireNonNull(guard);
        this.timeout = Objects.requireNonNull(timeout);
        this.facts = Objects.requireNonNull(facts);
    }

    public ProjectionRequirements searchRequirements(SearchCodeRequest request) {
        return CodeFactReadService.requirementsForSearchKinds(request.kinds());
    }

    public FactCollection searchCode(AdmittedContext admitted, SearchCodeRequest request) {
        SourceContext source = admitted.source();
        source.requireProjections(searchRequirements(request));
        SearchAccessPlan access = access(source);
        List<Bson> common = new ArrayList<>(List.of(base(source)));
        if (!request.kinds().isEmpty()) common.add(Filters.in("kind", names(request.kinds())));
        request.packagePrefix().ifPresent(value -> common.add(packageFilter("scopePackage", value)));
        request.path().ifPresent(value -> common.add(Filters.eq("sourcePath", value)));
        Bson exact = Filters.or(Filters.eq("displayName", request.query()), Filters.eq("signature", request.query()));
        Bson prefix = Filters.regex("displayName", "^" + Pattern.quote(request.query()));
        List<String> tokens = CodeFactTokenizer.tokenize(request.query());
        List<Bson> tokenFilters = tokens.stream().map(token -> Filters.regex("tokens", "^" + Pattern.quote(token))).toList();
        Bson token = tokenFilters.isEmpty() ? Filters.eq("factId", "") : Filters.and(tokenFilters);
        List<Phase> phases = new ArrayList<>();
        // Keep name and signature in one rank; Mongo can merge their equality-index branches.
        phases.add(new Phase(IndexCollections.SEARCH, access.authorized(Filters.and(Filters.and(common), exact)),
                List.of("kind", "canonical", "factId"), false, Optional.empty()));
        // Prefix/token ranges cannot supply the rank's kind/canonical/factId order directly.
        for (Bson rank : List.of(Filters.and(prefix, Filters.nor(exact)), Filters.and(token, Filters.nor(exact, prefix)))) {
            phases.add(new Phase(IndexCollections.SEARCH, access.authorized(Filters.and(Filters.and(common), rank)),
                    List.of("kind", "canonical", "factId"), false, Optional.of("search_generation_order")));
        }
        String binding = QueryCursorCodec.binding("search_code", admitted, List.of(request.query(), String.join(",", names(request.kinds())),
                request.packagePrefix().orElse(""), request.path().orElse(""), Integer.toString(request.page().limit())));
        Scanned<CompactFact> result = scan(phases, request.page(), binding, rows -> {
            Map<CodeFactId, CodeFactDetails> batch = facts.authoritativeAll(source, rows);
            List<Optional<CompactFact>> output = new ArrayList<>();
            for (Document row : rows) {
                CodeFactDetails fact = batch.get(new CodeFactId(text(row, "factId")));
                output.add(Optional.of(SemanticResultMapper.compact(fact)));
            }
            return output;
        });
        return new FactCollection(admitted.context(), result.items(), result.page());
    }

    public FactCollection getOutline(AdmittedContext admitted, OutlineRequest request) {
        SourceContext source = admitted.source();
        source.requireProjections(new ProjectionRequirements(Set.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS)));
        String path;
        Optional<SourceTypeIdentity> owner = Optional.empty();
        if (request.target().kind() == OutlineTargetKind.TYPE) {
            CodeFactDetails type = FactKindPolicy.require(facts.get(source, new CodeFactId(request.target().factId().orElseThrow())), Set.of(CodeFactKind.TYPE));
            if (!(type.fact().identity().canonicalIdentity() instanceof SourceTypeIdentity identity)) throw new IndexContractMismatchException();
            owner = Optional.of(identity);
            path = type.location().sourceFile();
        } else path = request.target().path().orElseThrow();
        SourceArtifactId artifact = facts.sourceFiles(source, Set.of(path)).get(path).sourceArtifactId();
        Set<CodeFactKind> kinds = request.kinds().isEmpty() ? FactKindPolicy.REFERENCE_TARGETS : request.kinds();
        List<Bson> filters = new ArrayList<>(List.of(base(source), Filters.eq("sourcePath", path), Filters.in("kind", names(kinds))));
        owner.ifPresent(type -> {
            String fqn = type.fullyQualifiedName();
            // TYPE symbols own themselves; direct nested declarations have one extra class-name segment.
            filters.add(Filters.or(Filters.and(Filters.ne("kind", "TYPE"), Filters.eq("owner", fqn)),
                    Filters.and(Filters.eq("kind", "TYPE"), Filters.regex("owner", "^" + Pattern.quote(fqn) + "[.$][^.$]+$"))));
        });
        Phase phase = new Phase(IndexCollections.SYMBOLS, access(source).authorized(Filters.and(filters)),
                List.of("range.range.start.line", "range.range.start.character", "symbolId"), true, Optional.of("symbol_file_range_order"));
        String binding = QueryCursorCodec.binding("get_outline", admitted, List.of(request.target().kind().name(),
                request.target().factId().orElse(""), path, String.join(",", names(kinds)), Integer.toString(request.page().limit())));
        Scanned<CompactFact> result = scan(List.of(phase), request.page(), binding, rows -> {
            List<CodeFactReadService.StoredFact> stored = rows.stream().map(row -> facts.storedFact(source, ProjectionName.SYMBOLS, row)).toList();
            List<Optional<CompactFact>> output = new ArrayList<>();
            for (CodeFactReadService.StoredFact fact : stored) {
                guard.requireVisible(source.selected(), fact.details().fact().identity());
                if (!artifact.equals(fact.artifact().orElseThrow(IndexContractMismatchException::new))) throw new IndexContractMismatchException();
                output.add(Optional.of(SemanticResultMapper.compact(fact.details())));
            }
            return output;
        });
        return new FactCollection(admitted.context(), result.items(), result.page());
    }

    public EntryPointCollection listEntryPoints(AdmittedContext admitted, EntryPointRequest request) {
        SourceContext source = admitted.source();
        source.requireProjections(new ProjectionRequirements(request.kind().filter(kind -> kind == EntryKind.EVENT).isPresent()
                ? Set.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS)
                : Set.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.ENTRY_POINTS)));
        List<EntryKind> kinds = request.kind().map(List::of).orElse(List.of(EntryKind.HTTP, EntryKind.EVENT, EntryKind.MQ, EntryKind.SCHEDULE));
        List<Phase> phases = new ArrayList<>();
        for (EntryKind kind : kinds) {
            List<Bson> predicates = new ArrayList<>(List.of(base(source)));
            request.packagePrefix().ifPresent(value -> predicates.add(packageFilter("scopePackage", value)));
            if (kind == EntryKind.EVENT) {
                predicates.add(Filters.eq("kind", "METHOD"));
                predicates.add(Filters.in("annotations.typeName", LISTENERS));
                request.eventType().ifPresent(value -> predicates.add(Filters.eq("fact.identity.canonicalIdentity.parameterTypes", value)));
                request.handlerName().ifPresent(value -> predicates.add(Filters.regex("name", "^" + Pattern.quote(value))));
                phases.add(new Phase(IndexCollections.SYMBOLS, access(source).authorizedMethod(Filters.and(predicates)),
                        List.of("canonical", "symbolId"), false, Optional.of("symbol_kind_canonical_order")));
            } else {
                predicates.add(Filters.eq("entryPoint.kind", kind.name()));
                request.handlerName().ifPresent(value -> predicates.add(Filters.regex("scopeMethod", "^" + Pattern.quote(value))));
                request.httpMethod().ifPresent(value -> predicates.add(Filters.in("httpMethod", value == HttpMethod.ALL
                        ? List.of("ALL") : List.of(value.name(), "ALL"))));
                request.path().ifPresent(value -> predicates.add(Filters.eq("path", value)));
                request.destination().ifPresent(value -> {
                    predicates.add(Filters.eq("entryPoint.trigger.destinationBroker", value.broker()));
                    predicates.add(Filters.eq("entryPoint.trigger.destination", value.destination()));
                });
                request.trigger().ifPresent(value -> predicates.add(Filters.eq("entryPoint.trigger.schedule", value)));
                phases.add(new Phase(IndexCollections.ENTRY_POINTS, access(source).authorizedEntryPoint(access(source).authorized(Filters.and(predicates))),
                        List.of("method", "entryPointId"), false, Optional.of("entry_point_kind_order")));
            }
        }
        String binding = QueryCursorCodec.binding("list_entry_points", admitted, List.of(request.kind().map(Enum::name).orElse(""),
                request.handlerName().orElse(""), request.packagePrefix().orElse(""), request.httpMethod().map(Enum::name).orElse(""),
                request.path().orElse(""), request.eventType().orElse(""), request.destination().map(ExternalTarget.Destination::broker).orElse(""),
                request.destination().map(ExternalTarget.Destination::destination).orElse(""),
                request.trigger().orElse(""), Integer.toString(request.page().limit())));
        Scanned<EntryPointItem> result = scan(phases, request.page(), binding, rows -> entryBatch(source, request, rows));
        return new EntryPointCollection(admitted.context(), result.items(), result.page());
    }

    private List<Optional<EntryPointItem>> entryBatch(SourceContext source, EntryPointRequest request, List<Document> rows) {
        boolean event = rows.getFirst().containsKey("symbolId");
        List<CodeFactReadService.StoredFact> stored = new ArrayList<>();
        List<Optional<CodeFactIdentity>> handlers = new ArrayList<>();
        List<Optional<EntryPointDocument>> entries = new ArrayList<>();
        Set<CodeFactIdentity> identities = new HashSet<>();
        for (Document row : rows) {
            if (event) {
                CodeFactReadService.StoredFact fact = facts.storedFact(source, ProjectionName.SYMBOLS, row);
                stored.add(fact);
                if (!(fact.details().fact().identity().canonicalIdentity() instanceof MethodTarget)
                        || fact.details().annotations().stream().noneMatch(annotation -> LISTENERS.contains(annotation.typeName()))) throw new IndexContractMismatchException();
                entries.add(Optional.empty());
                handlers.add(Optional.of(fact.details().fact().identity()));
                guard.requireVisible(source.selected(), fact.details().fact().identity());
            } else {
                EntryPointDocument entry = CodeFactReadService.decodeEntryPoint(row, source.selected(), template);
                stored.add(facts.storedEntryPoint(source, row, entry));
                entries.add(Optional.of(entry));
                CodeFactIdentity handler = new CodeFactIdentity(source.selected().repositoryId(), source.selected().revision(), CodeFactKind.METHOD, entry.method());
                if (visible(source, handler)) {
                    identities.add(handler);
                    handlers.add(Optional.of(handler));
                } else handlers.add(Optional.empty());
            }
        }
        facts.validateSources(source, stored);
        Map<CodeFactIdentity, CodeFactDetails> resolved = facts.getAllByIdentity(source, identities);
        List<Optional<EntryPointItem>> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (handlers.get(index).isEmpty()) { result.add(Optional.empty()); continue; }
            if (event) {
                CodeFactDetails handler = stored.get(index).details();
                MethodTarget method = (MethodTarget) handler.fact().identity().canonicalIdentity();
                Optional<String> eventType = request.eventType().or(() -> method.parameterTypes().stream().findFirst());
                result.add(Optional.of(new EntryPointItem(EntryKind.EVENT, Optional.empty(), SemanticResultMapper.compact(handler),
                        new EntryTrigger(Optional.empty(), Optional.empty(), eventType, Optional.empty(), Optional.empty()))));
            } else {
                EntryPointDocument entry = entries.get(index).orElseThrow();
                if (request.destination().filter(value -> !entry.trigger().destination().equals(Optional.of(value))).isPresent()
                        || request.trigger().filter(value -> !entry.trigger().schedule().equals(Optional.of(value))).isPresent()) {
                    result.add(Optional.empty()); continue;
                }
                result.add(Optional.of(new EntryPointItem(EntryKind.valueOf(entry.kind().name()), Optional.of(entry.fact().id().value()),
                        SemanticResultMapper.compact(resolved.get(handlers.get(index).orElseThrow())),
                        new EntryTrigger(entry.trigger().httpMethod().map(HttpMethod::valueOf), entry.trigger().httpPath(), Optional.empty(),
                                entry.trigger().destination(), entry.trigger().schedule()))));
            }
        }
        return result;
    }

    public RelationCollection findRelations(AdmittedContext admitted, RelationRequest request) {
        SourceContext source = admitted.source();
        source.requireProjections(new ProjectionRequirements(Set.of(ProjectionName.SEARCH, ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.RELATIONS)));
        CodeFactDetails target = facts.get(source, new CodeFactId(request.factId()));
        Set<CodeFactKind> allowed = switch (request.relation()) {
            case CALLERS, CALLEES -> FactKindPolicy.CALLS;
            case IMPLEMENTATIONS -> FactKindPolicy.IMPLEMENTATIONS;
            case REFERENCES -> FactKindPolicy.REFERENCE_TARGETS;
        };
        FactKindPolicy.require(target, allowed);
        List<RelationKind> kinds = switch (request.relation()) {
            case CALLERS, CALLEES -> List.of(RelationKind.CALLS);
            case IMPLEMENTATIONS -> target.fact().identity().kind() == CodeFactKind.TYPE ? List.of(RelationKind.IMPLEMENTS) : List.of(RelationKind.OVERRIDES);
            case REFERENCES -> List.of(RelationKind.REFERENCES);
        };
        boolean outbound = request.relation() == RelationMode.CALLEES;
        String endpoint = outbound ? target.fact().identity().canonicalForm() : new RelationTarget.Internal(target.fact().identity()).canonicalForm();
        List<Phase> phases = kinds.stream().map(kind -> new Phase(IndexCollections.RELATIONS,
                Filters.and(base(source), Filters.eq(outbound ? "from" : "target", endpoint), Filters.eq("kind", kind.name())),
                outbound ? List.of("sourcePath", "relationId") : List.of("from", "sourcePath", "relationId"), false,
                Optional.of(outbound ? "relation_from_kind_source" : "relation_target_kind_source"))).toList();
        String binding = QueryCursorCodec.binding("find_relations", admitted, List.of(request.relation().name(), request.factId(), Integer.toString(request.page().limit())));
        Scanned<RelationItem> result = scan(phases, request.page(), binding, rows -> relationBatch(source, rows));
        RelationEvidenceScope scope = switch (request.relation()) {
            case CALLERS, CALLEES -> RelationEvidenceScope.PROJECTED_CALLS;
            case IMPLEMENTATIONS -> RelationEvidenceScope.PROJECTED_IMPLEMENTATIONS;
            case REFERENCES -> RelationEvidenceScope.PROJECTED_REFERENCES;
        };
        return new RelationCollection(admitted.context(), request.relation(), result.items(), result.page(), scope);
    }

    private List<Optional<RelationItem>> relationBatch(SourceContext source, List<Document> rows) {
        List<RelationDocument> relations = rows.stream().map(row -> CodeFactReadService.decodeRelation(row, source.selected(), template)).toList();
        Set<CodeFactIdentity> identities = new HashSet<>();
        List<Boolean> admitted = new ArrayList<>();
        List<CodeFactReadService.StoredFact> occurrences = new ArrayList<>();
        for (RelationDocument relation : relations) {
            boolean permitted = visible(source, relation.from()) && (!(relation.target() instanceof RelationTarget.Internal internal) || visible(source, internal.identity()));
            admitted.add(permitted);
            if (!permitted) continue;
            identities.add(relation.from());
            if (relation.target() instanceof RelationTarget.Internal internal) identities.add(internal.identity());
            occurrences.add(new CodeFactReadService.StoredFact(new CodeFactDetails(source.selected(), relation.fact(), relation.range(), List.of(), Optional.empty()), Optional.of(relation.sourceArtifactId())));
        }
        Map<CodeFactIdentity, CodeFactDetails> endpoints = facts.getAllByIdentity(source, identities);
        facts.validateSources(source, occurrences);
        List<Optional<RelationItem>> output = new ArrayList<>();
        for (int index = 0; index < relations.size(); index++) {
            if (!admitted.get(index)) { output.add(Optional.empty()); continue; }
            RelationDocument relation = relations.get(index);
            RelationTargetItem target;
            if (relation.target() instanceof RelationTarget.Internal internal) target = new RelationTargetItem(TargetResolution.INTERNAL,
                    Optional.of(SemanticResultMapper.compact(endpoints.get(internal.identity()))), Optional.empty());
            else if (relation.target() instanceof RelationTarget.External external) {
                ExternalTarget value = external.target();
                boolean unresolved = value instanceof ExternalTarget.UnresolvedCall;
                String display = CodeFactDisplay.displayName(value);
                target = new RelationTargetItem(unresolved ? TargetResolution.UNRESOLVED : TargetResolution.EXTERNAL, Optional.empty(),
                        Optional.of(new ExternalTargetInfo(value.getClass().getSimpleName(), display,
                                unresolved ? Optional.empty() : Optional.of(value.canonicalForm()),
                                unresolved ? Optional.of(((ExternalTarget.UnresolvedCall) value).arity()) : Optional.empty())));
            } else throw new IndexContractMismatchException();
            output.add(Optional.of(new RelationItem(relation.kind(), SemanticResultMapper.compact(endpoints.get(relation.from())), target,
                    new Occurrence(relation.fact().id().value(), relation.range().sourceFile(), relation.range().range()))));
        }
        return output;
    }

    private boolean visible(SourceContext source, CodeFactIdentity identity) {
        try { guard.requireVisible(source.selected(), identity); return true; }
        catch (RepositoryNotFoundException denied) { return false; }
    }
    private SearchAccessPlan access(SourceContext source) { return guard.searchAccessPlan(source.selected().repositoryId().value()); }
    private static Bson base(SourceContext source) { return Filters.and(Filters.eq("repoId", source.selected().repositoryId().value()), Filters.eq("generationId", source.selected().generationId().value())); }
    private static Bson packageFilter(String field, String prefix) { return Filters.regex(field, "^" + Pattern.quote(prefix) + "(?:\\.|$)"); }
    private static List<String> names(Set<? extends Enum<?>> values) { return values.stream().map(Enum::name).sorted().toList(); }
    private static String text(Document row, String field) { return CodeFactReadService.requiredText(row, field); }

    private <T> Scanned<T> scan(List<Phase> phases, PageRequest page, String binding,
            Function<List<Document>, List<Optional<T>>> materialize) {
        int phaseIndex = 0;
        List<String> position = List.of();
        if (page.cursor().isPresent()) {
            List<String> encoded = QueryCursorCodec.decode(page.cursor().orElseThrow(), binding, 4);
            try { phaseIndex = Integer.parseInt(encoded.getFirst()); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid cursor", exception); }
            if (phaseIndex < 0 || phaseIndex >= phases.size()) throw new IllegalArgumentException("invalid cursor phase");
            Phase phase = phases.get(phaseIndex);
            position = encoded.subList(1, 1 + phase.keys().size());
            for (int index = 1 + phase.keys().size(); index < 4; index++) if (!encoded.get(index).isEmpty()) throw new IllegalArgumentException("invalid cursor position");
            Bson replay = Filters.and(phase.filter(), equalPosition(phase, position));
            try {
                if (Objects.isNull(template.getCollection(phase.collection()).find(replay).limit(1).maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).first())) throw new IllegalArgumentException("cursor fence is unavailable");
            } catch (MongoException | DataAccessException exception) { throw new SemanticIndexUnavailableException(exception); }
        }
        List<T> items = new ArrayList<>(page.limit());
        Optional<String> continuation = Optional.empty();
        try {
            while (phaseIndex < phases.size()) {
                Phase phase = phases.get(phaseIndex);
                Bson filter = position.isEmpty() ? phase.filter() : Filters.and(phase.filter(), after(phase, position));
                FindIterable<Document> find = template.getCollection(phase.collection()).find(filter).sort(Sorts.ascending(phase.keys()));
                phase.indexHint().ifPresent(find::hintString);
                List<Document> rows = find.limit(BATCH).batchSize(BATCH)
                        .maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).into(new ArrayList<>());
                if (rows.isEmpty()) { phaseIndex++; position = List.of(); continue; }
                List<Optional<T>> candidates = materialize.apply(rows);
                for (int index = 0; index < rows.size(); index++) {
                    List<String> current = position(phase, rows.get(index));
                    if (!position.isEmpty() && compare(phase, current, position) <= 0) throw new IndexContractMismatchException();
                    if (candidates.get(index).isPresent()) {
                        if (items.size() == page.limit()) return new Scanned<>(List.copyOf(items), new Page(items.size(), true, continuation));
                        items.add(candidates.get(index).orElseThrow());
                        continuation = Optional.of(cursor(binding, phaseIndex, current));
                    }
                    position = current;
                }
            }
            return new Scanned<>(List.copyOf(items), new Page(items.size(), false, Optional.empty()));
        } catch (MongoException | DataAccessException exception) { throw new SemanticIndexUnavailableException(exception); }
    }

    private static String cursor(String binding, int phase, List<String> position) {
        List<String> fields = new ArrayList<>(List.of(Integer.toString(phase)));
        fields.addAll(position);
        while (fields.size() < 4) fields.add("");
        return QueryCursorCodec.encode(binding, fields);
    }
    private static Object value(Phase phase, int index, String value) {
        if (phase.numericRange() && index < 2) {
            try { int coordinate = Integer.parseInt(value); if (coordinate < 0) throw new IllegalArgumentException("invalid cursor coordinate"); return coordinate; }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid cursor coordinate", exception); }
        }
        if (value.isEmpty()) throw new IllegalArgumentException("empty cursor position");
        return value;
    }
    private static Bson equalPosition(Phase phase, List<String> position) {
        List<Bson> filters = new ArrayList<>();
        for (int index = 0; index < position.size(); index++) filters.add(Filters.eq(phase.keys().get(index), value(phase, index, position.get(index))));
        return Filters.and(filters);
    }
    private static Bson after(Phase phase, List<String> position) {
        List<Bson> alternatives = new ArrayList<>();
        for (int index = 0; index < position.size(); index++) {
            List<Bson> terms = new ArrayList<>();
            for (int prior = 0; prior < index; prior++) terms.add(Filters.eq(phase.keys().get(prior), value(phase, prior, position.get(prior))));
            terms.add(Filters.gt(phase.keys().get(index), value(phase, index, position.get(index))));
            alternatives.add(Filters.and(terms));
        }
        return Filters.or(alternatives);
    }
    private static List<String> position(Phase phase, Document row) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < phase.keys().size(); index++) {
            Object object = row;
            for (String segment : phase.keys().get(index).split("\\.")) {
                if (!(object instanceof Document document)) throw new IndexContractMismatchException();
                object = document.get(segment);
            }
            if (phase.numericRange() && index < 2) {
                if (!(object instanceof Integer number) || number < 0) throw new IndexContractMismatchException();
                result.add(number.toString());
            } else {
                if (!(object instanceof String string) || string.isEmpty()) throw new IndexContractMismatchException();
                result.add(string);
            }
        }
        return List.copyOf(result);
    }
    private static int compare(Phase phase, List<String> left, List<String> right) {
        for (int index = 0; index < left.size(); index++) {
            int comparison = phase.numericRange() && index < 2 ? Integer.compare(Integer.parseInt(left.get(index)), Integer.parseInt(right.get(index)))
                    : compareUnicode(left.get(index), right.get(index));
            if (comparison != 0) return comparison;
        }
        return 0;
    }
    private static int compareUnicode(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }
    private record Phase(String collection, Bson filter, List<String> keys, boolean numericRange, Optional<String> indexHint) { }
    private record Scanned<T>(List<T> items, Page page) { }
}
