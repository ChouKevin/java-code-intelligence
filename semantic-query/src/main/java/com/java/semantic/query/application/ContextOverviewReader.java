package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceModule;
import com.java.semantic.query.application.SemanticQueryContract.BoundedSummary;
import com.java.semantic.query.application.SemanticQueryContract.ContextOverview;
import com.java.semantic.query.application.SemanticQueryContract.Coverage;
import com.java.semantic.query.application.SemanticQueryContract.CoverageScope;
import com.java.semantic.query.application.SemanticQueryContract.EntryKind;
import com.java.semantic.query.application.SemanticQueryContract.EntryKindCount;
import com.java.semantic.query.application.SemanticQueryContract.ModuleSummary;
import com.java.semantic.query.application.SemanticQueryContract.PackageSummary;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Aggregates;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Sealed summaries or bounded server-side aggregates; never materializes generation-file inventories. */
final class ContextOverviewReader {
    private static final ProjectionRequirements ENTRY_COUNTS = new ProjectionRequirements(EnumSet.of(ProjectionName.ENTRY_POINTS));
    private final MongoTemplate template;
    private final Duration timeout;
    ContextOverviewReader(MongoTemplate template, Duration timeout) {
        this.template = Objects.requireNonNull(template);
        this.timeout = Objects.requireNonNull(timeout);
    }

    ContextOverview overview(SourceContext source, SearchAccessPlan access, int limit) {
        List<SourceModule> modules = source.modules().orElseThrow(IndexContractMismatchException::new);
        if (!access.isUnrestricted()) return scoped(source, access, modules, limit);
        SourceCoverage counts = source.coverage();
        List<ModuleSummary> moduleItems = modules.stream().sorted(Comparator.comparing(SourceModule::path))
                .limit((long) limit + 1).map(module -> new ModuleSummary(module.path(), bounded(module.sourceRoots(), limit))).toList();
        List<PackageSummary> packages = source.structure().packageCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit((long) limit + 1).map(entry -> new PackageSummary(entry.getKey(), entry.getValue())).toList();
        List<EntryKindCount> entries = source.structure().entryPointKindCounts().entrySet().stream()
                .map(entry -> {
                    EntryKind kind = EntryKind.valueOf(entry.getKey());
                    if (kind == EntryKind.EVENT) throw new IndexContractMismatchException();
                    return new EntryKindCount(kind, entry.getValue());
                })
                .sorted(Comparator.comparing(EntryKindCount::kind)).toList();
        return new ContextOverview(bounded(moduleItems, limit), bounded(source.structure().importedSourceRoots().stream().sorted().toList(), limit),
                bounded(packages, limit), bounded(entries, limit), List.of(EntryKind.EVENT),
                new Coverage(CoverageScope.GENERATION, counts.readableCode(), Optional.of(counts.excludedOrUnsupported()),
                        counts.extractionIssues(), Optional.of(counts.unresolvedSemanticEvidence()), List.of()));
    }

    private ContextOverview scoped(SourceContext source, SearchAccessPlan access, List<SourceModule> modules, int limit) {
        Bson generation = Filters.and(Filters.eq("repoId", source.selected().repositoryId().value()),
                Filters.eq("generationId", source.selected().generationId().value()));
        List<Document> moduleValues = modules.stream().map(module -> new Document("path", module.path())
                .append("sourceRoots", module.sourceRoots())).toList();
        Document facets = new Document("counts", List.of(new Document("$group", new Document("_id", 0)
                .append("files", new Document("$sum", 1))
                .append("issues", new Document("$sum", new Document("$cond", List.of(new Document("$ne", List.of("$extractionIssueCode", "")), 1, 0)))))))
                .append("packages", List.of(new Document("$unwind", "$scopePackages"),
                        new Document("$group", new Document("_id", "$scopePackages").append("count", new Document("$sum", 1))),
                        new Document("$sort", new Document("_id", 1)), new Document("$limit", limit + 1)))
                .append("roots", List.of(new Document("$project", new Document("roots", matchingRoots(new Document("$literal", source.structure().importedSourceRoots())))),
                        new Document("$unwind", "$roots"), new Document("$group", new Document("_id", "$roots")),
                        new Document("$sort", new Document("_id", 1)), new Document("$limit", limit + 1)))
                .append("modules", List.of(new Document("$project", new Document("modules", new Document("$map",
                                new Document("input", new Document("$literal", moduleValues)).append("as", "module")
                                        .append("in", new Document("path", "$$module.path").append("roots", matchingRoots("$$module.sourceRoots")))))),
                        new Document("$unwind", "$modules"), new Document("$unwind", "$modules.roots"),
                        new Document("$group", new Document("_id", "$modules.path").append("roots", new Document("$addToSet", "$modules.roots"))),
                        new Document("$sort", new Document("_id", 1)), new Document("$limit", limit + 1),
                        new Document("$project", new Document("roots", new Document("$slice", List.of(
                                new Document("$sortArray", new Document("input", "$roots").append("sortBy", 1)), limit + 1))))));
        Document result = template.getCollection(IndexCollections.GENERATION_FILES)
                .aggregate(List.of(Aggregates.match(access.authorizedSource(generation)), new Document("$facet", facets)))
                .maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(result)) throw new IndexContractMismatchException();
        List<Document> counts = rows(result, "counts");
        long readable = counts.isEmpty() ? 0 : number(counts.getFirst(), "files");
        long issues = counts.isEmpty() ? 0 : number(counts.getFirst(), "issues");
        List<PackageSummary> packages = rows(result, "packages").stream()
                .map(row -> new PackageSummary(row.getString("_id"), number(row, "count"))).toList();
        List<String> roots = rows(result, "roots").stream().map(row -> row.getString("_id")).toList();
        List<ModuleSummary> moduleItems = rows(result, "modules").stream()
                .map(row -> new ModuleSummary(row.getString("_id"), bounded(row.getList("roots", String.class), limit))).toList();
        source.requireProjections(ENTRY_COUNTS);
        Bson entries = access.authorizedEntryPoint(Filters.and(generation,
                Filters.eq("entryPoint.repositoryId", source.selected().repositoryId().value()),
                Filters.eq("entryPoint.generationId", source.selected().generationId().value()),
                Filters.eq("entryPoint.fact.identity.revision", source.selected().revision().value())));
        List<EntryKindCount> entryCounts = new ArrayList<>();
        for (Document row : template.getCollection(IndexCollections.ENTRY_POINTS).aggregate(List.of(Aggregates.match(entries),
                new Document("$group", new Document("_id", "$entryPoint.kind").append("count", new Document("$sum", 1)))))
                .maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            EntryKind kind = EntryKind.valueOf(row.getString("_id"));
            if (kind == EntryKind.EVENT) throw new IndexContractMismatchException();
            entryCounts.add(new EntryKindCount(kind, number(row, "count")));
        }
        entryCounts.sort(Comparator.comparing(EntryKindCount::kind));
        return new ContextOverview(bounded(moduleItems, limit), bounded(roots, limit), bounded(packages, limit),
                bounded(entryCounts, limit), List.of(EntryKind.EVENT),
                new Coverage(CoverageScope.AUTHORIZED_SOURCE_FILES, readable, Optional.empty(), issues, Optional.empty(),
                        List.of("excludedOrUnsupported", "unresolvedSemanticEvidence")));
    }

    private static Document matchingRoots(Object roots) {
        return new Document("$filter", new Document("input", roots).append("as", "root")
                .append("cond", new Document("$or", List.of(new Document("$eq", List.of("$$root", ".")),
                        new Document("$eq", List.of("$sourcePath", "$$root")),
                        new Document("$eq", List.of(new Document("$indexOfCP", List.of("$sourcePath",
                                new Document("$concat", List.of("$$root", "/")))), 0))))));
    }
    private static List<Document> rows(Document result, String field) {
        List<Document> rows = result.getList(field, Document.class);
        if (Objects.isNull(rows)) throw new IndexContractMismatchException();
        return rows;
    }
    private static long number(Document row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number number) || number.longValue() < 0) throw new IndexContractMismatchException();
        return number.longValue();
    }
    private static <T> BoundedSummary<T> bounded(List<T> values, int limit) {
        return new BoundedSummary<>(values.size() > limit ? values.subList(0, limit) : values, values.size() > limit);
    }
}
