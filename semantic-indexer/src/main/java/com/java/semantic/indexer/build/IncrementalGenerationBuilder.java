package com.java.semantic.indexer.build;

import com.java.semantic.indexer.incremental.IncrementalIndexPlan;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Chooses a contract-compatible parent, copies its unaffected facts, and returns only sources requiring export. */
public final class IncrementalGenerationBuilder {
    private final MongoTemplate template;
    private final IncrementalIndexPlanner planner;
    private final ParentGenerationCopier copier;
    private final SourceIndexBatchDocumentMapper mapper;

    public IncrementalGenerationBuilder(MongoTemplate template, IncrementalIndexPlanner planner, ParentGenerationCopier copier) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.planner = Objects.requireNonNull(planner, "incremental planner is required");
        this.copier = Objects.requireNonNull(copier, "parent generation copier is required");
        this.mapper = new SourceIndexBatchDocumentMapper(template.getConverter());
    }

    /** Uses the selected checkout inventory as the sole source set for planning and full-fallback selection. */
    public BuildSelection assemble(IndexJob job, MongoGenerationWriter.GenerationLease lease, FullIndexPlan selectedRevisionPlan) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(lease, "generation lease is required");
        FullIndexPlan completePlan = Objects.requireNonNull(selectedRevisionPlan, "selected revision plan is required");
        List<String> selectedPaths = completePlan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath).sorted().toList();
        if (job.rebuild()) {
            return BuildSelection.full(fullPlan(selectedPaths, "EXPLICIT_REBUILD"), completePlan);
        }
        Optional<Parent> parent = compatibleParent(job);
        if (parent.isEmpty()) {
            return BuildSelection.full(fullPlan(selectedPaths, "PARENT_CONTRACT_MISMATCH"), completePlan);
        }
        Parent current = parent.orElseThrow();
        IncrementalIndexPlan plan = planner.plan(readPublishedIndex(job, current), current.revision().value(), job.revision().value(), selectedPaths);
        if (plan.fullRepository()) {
            return BuildSelection.full(plan, completePlan);
        }
        IncrementalIndexPlan adjusted = safePlan(plan, completePlan, job, current);
        copier.copy(lease, current.generationId(), current.revision(), job.revision(), adjusted.copyPaths());
        return new BuildSelection(true, adjusted, subset(completePlan, adjusted.reanalyzePaths()));
    }

    /** Reconciles planner output with the selected checkout and validates every copied parent artifact. */
    private IncrementalIndexPlan safePlan(IncrementalIndexPlan plan, FullIndexPlan completePlan, IndexJob job, Parent parent) {
        Map<String, FullIndexPlan.SourceInput> selected = selectedSources(completePlan);
        Set<String> selectedPaths = selected.keySet();
        TreeSet<String> reanalyze = new TreeSet<>(plan.reanalyzePaths());
        reanalyze.retainAll(selectedPaths);
        TreeSet<String> copy = new TreeSet<>(plan.copyPaths());
        copy.retainAll(selectedPaths);
        copy.removeAll(reanalyze);
        for (String path : selectedPaths) {
            if (!copy.contains(path) && !reanalyze.contains(path)) {
                reanalyze.add(path);
            }
        }
        for (String path : Set.copyOf(copy)) {
            if (!matchesParentArtifact(job, parent, path, selected.get(path))) {
                copy.remove(path);
                reanalyze.add(path);
            }
        }
        return new IncrementalIndexPlan(false, List.copyOf(reanalyze), List.copyOf(copy), plan.deletedPaths(), plan.diagnosticReasons());
    }

    private static Map<String, FullIndexPlan.SourceInput> selectedSources(FullIndexPlan completePlan) {
        TreeMap<String, FullIndexPlan.SourceInput> selected = new TreeMap<>();
        for (FullIndexPlan.SourceInput source : completePlan.sources()) {
            selected.put(source.sourcePath(), source);
        }
        return Map.copyOf(selected);
    }

    private boolean matchesParentArtifact(IndexJob job, Parent parent, String path, FullIndexPlan.SourceInput selected) {
        Document scope = new Document("repoId", job.repositoryId().value()).append("generationId", parent.generationId().value())
                .append("sourcePath", path);
        Document document = template.getCollection(IndexCollections.GENERATION_FILES).find(scope).first();
        if (Objects.isNull(document)) {
            return false;
        }
        GenerationFileDocument parentFile = template.getConverter().read(GenerationFileDocument.class, document);
        return parentFile.sourceArtifactId().equals(selected.contentArtifact().id())
                && parentFile.contentHash().equals(selected.contentArtifact().contentHash());
    }

    private Optional<Parent> compatibleParent(IndexJob job) {
        Document claimedJob = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document("jobId", job.id().value())
                .append("repoId", job.repositoryId().value()).append("active", true).append("workerId", job.workerId().orElseThrow())
                .append("fence", job.fence().orElseThrow().value()).append("buildParentCaptured", true)).first();
        if (Objects.isNull(claimedJob)) {
            return Optional.empty();
        }
        Document parentPointer = claimedJob.get("buildParent", Document.class);
        if (Objects.isNull(parentPointer)) {
            return Optional.empty();
        }
        String parentGeneration = parentPointer.getString("generationId");
        String parentRevision = parentPointer.getString("revision");
        String parentDigest = parentPointer.getString("manifestDigest");
        if (Objects.isNull(parentGeneration) || Objects.isNull(parentRevision) || Objects.isNull(parentDigest)) {
            return Optional.empty();
        }
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId", job.repositoryId().value())
                .append("generationId", parentGeneration).append("sourceRevision", parentRevision)
                .append("identityDigest", parentDigest).append("writeState", "SEALED_VALID")).first();
        if (!compatible(manifest)) {
            return Optional.empty();
        }
        return Optional.of(new Parent(new GenerationId(parentGeneration), new RepositoryRevision(parentRevision)));
    }

    private IncrementalIndexPlanner.PublishedIndex readPublishedIndex(IndexJob job, Parent parent) {
        Document scope = new Document("repoId", job.repositoryId().value()).append("generationId", parent.generationId().value());
        List<String> sources = template.getCollection(IndexCollections.GENERATION_FILES).find(scope).map(document -> document.getString("sourcePath"))
                .into(new ArrayList<>());
        List<SymbolDocument> symbols = template.getCollection(IndexCollections.SYMBOLS).find(scope).map(document ->
                template.getConverter().read(SymbolDocument.class, document)).into(new ArrayList<>());
        List<com.java.semantic.model.index.RelationDocument> relations = template.getCollection(IndexCollections.RELATIONS).find(scope)
                .map(mapper::reconstructRelation).into(new ArrayList<>());
        List<com.java.semantic.model.index.EntryPointDocument> entryPoints = template.getCollection(IndexCollections.ENTRY_POINTS).find(scope)
                .map(mapper::reconstructEntryPoint).into(new ArrayList<>());
        List<com.java.semantic.model.index.SearchDocument> search = template.getCollection(IndexCollections.SEARCH).find(scope)
                .map(mapper::reconstructSearch).into(new ArrayList<>());
        return IncrementalIndexPlanner.PublishedIndex.fromPublishedFacts(sources, symbols, relations, entryPoints, search);
    }

    private static boolean compatible(Document manifest) {
        if (Objects.isNull(manifest)) {
            return false;
        }
        Number schemaVersion = manifest.get("schemaVersion", Number.class);
        if (Objects.isNull(schemaVersion) || schemaVersion.intValue() != IndexSchemaContract.SCHEMA_VERSION) {
            return false;
        }
        List<Document> stored = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(stored)) {
            return false;
        }
        java.util.TreeMap<String, Integer> versions = new java.util.TreeMap<>();
        for (Document entry : stored) {
            String name = entry.getString("name");
            Number version = entry.get("version", Number.class);
            if (Objects.isNull(name) || Objects.isNull(version)) {
                return false;
            }
            versions.put(name, version.intValue());
        }
        return IndexSchemaContract.requiredProjectionVersions().equals(Map.copyOf(versions));
    }

    private static IncrementalIndexPlan fullPlan(List<String> selectedPaths, String reason) {
        return new IncrementalIndexPlan(true, selectedPaths, List.of(), List.of(), List.of(reason));
    }

    private static FullIndexPlan subset(FullIndexPlan completePlan, List<String> sourcePaths) {
        java.util.Set<String> selected = java.util.Set.copyOf(sourcePaths);
        return new FullIndexPlan(completePlan.repositoryRoot(), completePlan.sources().stream()
                .filter(source -> selected.contains(source.sourcePath())).toList());
    }

    public record BuildSelection(boolean incremental, IncrementalIndexPlan plan, FullIndexPlan exportPlan) {
        public BuildSelection {
            plan = Objects.requireNonNull(plan, "incremental plan is required");
            exportPlan = Objects.requireNonNull(exportPlan, "export plan is required");
        }

        public static BuildSelection full(IncrementalIndexPlan plan, FullIndexPlan completePlan) {
            return new BuildSelection(false, plan, completePlan);
        }

        public List<String> exportPaths() {
            return exportPlan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath).toList();
        }
    }

    private record Parent(GenerationId generationId, RepositoryRevision revision) { }
}
