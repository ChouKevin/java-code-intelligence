package com.java.semantic.indexer.incremental;

import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.CodeFactIdentity;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Computes safe affected source paths using only revision-pinned diff and published index facts. */
public final class IncrementalIndexPlanner {
    private final GitRevisionDiff revisionDiff;
    private final SourceContractChangeDetector contractChangeDetector;
    private final ModuleLocator moduleLocator;

    public IncrementalIndexPlanner(GitRevisionDiff revisionDiff, SourceContractChangeDetector contractChangeDetector,
                                   ModuleLocator moduleLocator) {
        this.revisionDiff = Objects.requireNonNull(revisionDiff, "revision diff is required");
        this.contractChangeDetector = Objects.requireNonNull(contractChangeDetector, "contract change detector is required");
        this.moduleLocator = Objects.requireNonNull(moduleLocator, "module locator is required");
    }

    /**
     * Plans one selected revision from its authoritative supported-source inventory, separately from parent facts.
     */
    public IncrementalIndexPlan plan(PublishedIndex publishedIndex, String publishedRevision, String selectedRevision,
                                     Collection<String> selectedSupportedSourcePaths) {
        PublishedIndex index = Objects.requireNonNull(publishedIndex, "published index is required");
        List<ChangedSource> changes = List.copyOf(revisionDiff.diff(Objects.requireNonNull(publishedRevision,
                "published revision is required"), Objects.requireNonNull(selectedRevision, "selected revision is required")));
        TreeSet<String> selectedSources = sortedPaths(selectedSupportedSourcePaths, "selected supported source paths");
        TreeSet<String> reanalyze = new TreeSet<>();
        TreeSet<String> deleted = new TreeSet<>();
        TreeSet<String> reasons = new TreeSet<>();
        for (ChangedSource change : changes) {
            if (isBuildInput(change)) {
                if (!selectBuildClosure(change, reanalyze, reasons)) {
                    return full(selectedSources, "UNCERTAIN_MODULE_CLOSURE:" + pathOf(change), deleted);
                }
                continue;
            }
            if (!change.affectsSupportedSource()) {
                continue;
            }
            if (change.kind() == ChangeKind.DELETE || change.kind() == ChangeKind.RENAME) {
                deleted.add(change.oldPath());
            }
            if (change.kind() != ChangeKind.DELETE && selectedSources.contains(change.newPath())) {
                reanalyze.add(change.newPath());
            }
            SourceContractChangeDetector.Impact impact = contractChangeDetector.detect(change, index.declarations());
            if (impact.uncertain()) {
                return full(selectedSources, "UNCERTAIN_CONTRACT:" + pathOf(change), deleted);
            }
            if (impact.publicDeclaration() || change.kind() == ChangeKind.DELETE || change.kind() == ChangeKind.RENAME) {
                reanalyze.addAll(index.dependentPaths(change.oldPath()));
                reasons.add("DEPENDENTS:" + pathOf(change));
            }
            if (impact.frameworkAnnotation()) {
                reanalyze.addAll(index.discoveryPaths());
                reasons.add("DISCOVERY_DEPENDENTS:" + pathOf(change));
            }
            reasons.add((impact.publicDeclaration() ? "PUBLIC_CONTRACT:" : "BODY_OR_PRIVATE:") + pathOf(change));
        }
        reanalyze.retainAll(selectedSources);
        reanalyze.removeAll(deleted);
        TreeSet<String> copy = new TreeSet<>(index.supportedSourcePaths());
        copy.retainAll(selectedSources);
        copy.removeAll(reanalyze);
        copy.removeAll(deleted);
        return new IncrementalIndexPlan(false, List.copyOf(reanalyze), List.copyOf(copy), List.copyOf(deleted), List.copyOf(reasons));
    }

    private boolean selectBuildClosure(ChangedSource change, TreeSet<String> reanalyze, TreeSet<String> reasons) {
        Optional<String> owner = moduleLocator.locate(pathOf(change));
        if (owner.isEmpty()) {
            return false;
        }
        Optional<Set<String>> closure = moduleLocator.reverseDependencyClosure(owner.orElseThrow());
        if (closure.isEmpty() || closure.orElseThrow().isEmpty()) {
            return false;
        }
        for (String module : closure.orElseThrow()) {
            Optional<Set<String>> moduleSources = moduleLocator.supportedSources(module);
            if (moduleSources.isEmpty()) {
                return false;
            }
            reanalyze.addAll(moduleSources.orElseThrow());
        }
        reasons.add("BUILD_INPUT_CLOSURE:" + pathOf(change));
        return true;
    }

    private static IncrementalIndexPlan full(Collection<String> selectedSupportedSourcePaths, String reason,
                                             Collection<String> deletedPaths) {
        TreeSet<String> reanalyze = sortedPaths(selectedSupportedSourcePaths, "selected supported source paths");
        TreeSet<String> deleted = sortedPaths(deletedPaths, "deleted paths");
        deleted.removeAll(reanalyze);
        return new IncrementalIndexPlan(true, List.copyOf(reanalyze), List.of(), List.copyOf(deleted), List.of(reason));
    }

    private static String pathOf(ChangedSource change) {
        return change.newPath().isEmpty() ? change.oldPath() : change.newPath();
    }

    private static boolean isBuildInput(ChangedSource change) {
        String path = pathOf(change);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return filename.equals("pom.xml") || filename.equals("build.gradle") || filename.equals("build.gradle.kts")
                || filename.equals("settings.gradle") || filename.equals("settings.gradle.kts")
                || filename.equals("gradle.properties") || filename.equals("maven.config");
    }

    private static TreeSet<String> sortedPaths(Collection<String> paths, String name) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String path : Objects.requireNonNull(paths, name + " are required")) {
            sorted.add(Objects.requireNonNull(path, name + " must not contain null"));
        }
        return sorted;
    }

    /** Read snapshot projected from the published manifest and fact collections; the planner never queries Mongo or JDT LS. */
    public record PublishedIndex(List<String> supportedSourcePaths, List<SymbolDocument> declarations,
                                 Map<String, Set<String>> dependentSourcePaths) {
        public PublishedIndex {
            supportedSourcePaths = sorted(supportedSourcePaths, "supported source paths");
            declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations are required"));
            dependentSourcePaths = immutableMap(dependentSourcePaths);
        }

        Set<String> dependentPaths(String sourcePath) {
            return dependentSourcePaths.getOrDefault(sourcePath, Set.of());
        }

        Set<String> discoveryPaths() {
            TreeSet<String> paths = new TreeSet<>();
            for (Set<String> dependents : dependentSourcePaths.values()) {
                paths.addAll(dependents);
            }
            return Set.copyOf(paths);
        }

        /** Creates the planner snapshot directly from the immutable projections published for the parent generation. */
        public static PublishedIndex fromPublishedFacts(List<String> supportedSourcePaths,
                                                        List<SymbolDocument> declarations,
                                                        List<RelationDocument> relations,
                                                        List<EntryPointDocument> entryPoints,
                                                        List<SearchDocument> searchDocuments) {
            List<SymbolDocument> requiredDeclarations = List.copyOf(Objects.requireNonNull(declarations,
                    "declarations are required"));
            List<RelationDocument> requiredRelations = List.copyOf(Objects.requireNonNull(relations,
                    "relations are required"));
            List<EntryPointDocument> requiredEntryPoints = List.copyOf(Objects.requireNonNull(entryPoints,
                    "entry points are required"));
            List<SearchDocument> requiredSearch = List.copyOf(Objects.requireNonNull(searchDocuments,
                    "search documents are required"));
            Map<CodeFactIdentity, String> declarationPaths = new java.util.HashMap<>();
            for (SymbolDocument declaration : requiredDeclarations) {
                declarationPaths.put(declaration.fact().identity(), declaration.range().sourceFile());
            }
            java.util.TreeMap<String, Set<String>> dependencies = new java.util.TreeMap<>();
            for (RelationDocument relation : requiredRelations) {
                if (!impactRelation(relation.kind()) || !(relation.target() instanceof RelationTarget.Internal target)) {
                    continue;
                }
                String targetPath = declarationPaths.get(target.identity());
                if (targetPath != null) { // cs-allow Map contract uses null for an absent key.
                    dependencies.computeIfAbsent(targetPath, ignored -> new TreeSet<>()).add(relation.range().sourceFile());
                }
            }
            TreeSet<String> discoveryPaths = new TreeSet<>();
            for (EntryPointDocument entryPoint : requiredEntryPoints) {
                discoveryPaths.add(entryPoint.range().sourceFile());
            }
            for (SearchDocument search : requiredSearch) {
                search.scope().sourcePath().ifPresent(discoveryPaths::add);
            }
            if (!discoveryPaths.isEmpty()) {
                dependencies.put("", Set.copyOf(discoveryPaths));
            }
            return new PublishedIndex(supportedSourcePaths, requiredDeclarations, dependencies);
        }

        private static boolean impactRelation(RelationKind kind) {
            return kind == RelationKind.REFERENCES || kind == RelationKind.CALLS || kind == RelationKind.USES_TYPE
                    || kind == RelationKind.IMPLEMENTS || kind == RelationKind.OVERRIDES || kind == RelationKind.EXTENDS;
        }

        private static List<String> sorted(Collection<String> values, String name) {
            return List.copyOf(Objects.requireNonNull(values, name + " are required").stream()
                    .map(value -> Objects.requireNonNull(value, name + " must not contain null")).sorted().toList());
        }

        private static Map<String, Set<String>> immutableMap(Map<String, Set<String>> values) {
            Map<String, Set<String>> required = Objects.requireNonNull(values, "dependent source paths are required");
            java.util.TreeMap<String, Set<String>> sorted = new java.util.TreeMap<>();
            for (Map.Entry<String, Set<String>> entry : required.entrySet()) {
                sorted.put(Objects.requireNonNull(entry.getKey(), "dependent source path key is required"),
                        Set.copyOf(Objects.requireNonNull(entry.getValue(), "dependent source paths are required")));
            }
            return Map.copyOf(sorted);
        }
    }
}
