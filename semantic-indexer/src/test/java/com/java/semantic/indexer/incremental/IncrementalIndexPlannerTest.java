package com.java.semantic.indexer.incremental;

import com.java.semantic.model.index.SymbolDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalIndexPlannerTest {

    @Test
    void should_reanalyze_selected_only_added_source() {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(
                ChangedSource.add("api/B.java", "public class B {}"));
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff,
                SourceContractChangeDetector.lightweight(), noModules());

        IncrementalIndexPlan plan = planner.plan(publishedIndex(List.of("api/A.java", "app/Caller.java")),
                "published", "selected", List.of("api/A.java", "api/B.java", "app/Caller.java"));

        assertThat(plan.reanalyzePaths()).containsExactly("api/B.java");
        assertThat(plan.copyPaths()).containsExactly("api/A.java", "app/Caller.java");
        assertThat(plan.deletedPaths()).isEmpty();
    }

    @Test
    void should_reanalyze_selected_only_rename_destination_and_delete_old_path() {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(
                ChangedSource.rename("api/A.java", "api/B.java", "public class A {}", "public class B {}"));
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff,
                SourceContractChangeDetector.lightweight(), noModules());

        IncrementalIndexPlan plan = planner.plan(publishedIndex(List.of("api/A.java", "app/Caller.java")),
                "published", "selected", List.of("api/B.java", "app/Caller.java"));

        assertThat(plan.reanalyzePaths()).containsExactly("api/B.java", "app/Caller.java");
        assertThat(plan.copyPaths()).isEmpty();
        assertThat(plan.deletedPaths()).containsExactly("api/A.java");
    }

    @Test
    void should_full_fallback_without_deleted_parent_source_when_later_change_is_uncertain() {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(
                ChangedSource.delete("api/Deleted.java", "public class Deleted {}"),
                ChangedSource.modify("api/Current.java", "old", "new"));
        SourceContractChangeDetector detector = (changedSource, declarations) -> changedSource.kind() == ChangeKind.MODIFY
                ? SourceContractChangeDetector.Impact.uncertainChange()
                : SourceContractChangeDetector.Impact.publicDeclarationChange();
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff, detector, noModules());

        IncrementalIndexPlan plan = planner.plan(publishedIndex(List.of("api/Current.java", "api/Deleted.java")),
                "published", "selected", List.of("api/Current.java"));

        assertThat(plan.fullRepository()).isTrue();
        assertThat(plan.reanalyzePaths()).containsExactly("api/Current.java");
        assertThat(plan.copyPaths()).isEmpty();
        assertThat(plan.deletedPaths()).containsExactly("api/Deleted.java");
        assertThat(plan.diagnosticReasons()).containsExactly("UNCERTAIN_CONTRACT:api/Current.java");
    }

    @Test
    void should_full_fallback_include_parent_deletions_after_earlier_uncertainty() {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(
                ChangedSource.modify("api/Current.java", "old", "new"),
                ChangedSource.delete("api/Deleted.java", "public class Deleted {}"),
                ChangedSource.rename("api/Renamed.java", "api/RenamedCurrent.java", "public class Renamed {}",
                        "public class RenamedCurrent {}"));
        SourceContractChangeDetector detector = (changedSource, declarations) -> changedSource.kind() == ChangeKind.MODIFY
                ? SourceContractChangeDetector.Impact.uncertainChange()
                : SourceContractChangeDetector.Impact.publicDeclarationChange();
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff, detector, noModules());

        IncrementalIndexPlan plan = planner.plan(publishedIndex(List.of("api/Current.java", "api/Deleted.java",
                        "api/Renamed.java")), "published", "selected",
                List.of("api/Current.java", "api/RenamedCurrent.java"));

        assertThat(plan.fullRepository()).isTrue();
        assertThat(plan.reanalyzePaths()).containsExactly("api/Current.java", "api/RenamedCurrent.java");
        assertThat(plan.copyPaths()).isEmpty();
        assertThat(plan.deletedPaths()).containsExactly("api/Deleted.java", "api/Renamed.java");
        assertThat(plan.diagnosticReasons()).containsExactly("UNCERTAIN_CONTRACT:api/Current.java");
    }

    @Test
    void should_full_fallback_include_selected_only_added_source() {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(
                ChangedSource.add("api/B.java", "public class B {}"),
                ChangedSource.modify("api/A.java", "old", "new"));
        SourceContractChangeDetector detector = (changedSource, declarations) -> changedSource.kind() == ChangeKind.MODIFY
                ? SourceContractChangeDetector.Impact.uncertainChange()
                : SourceContractChangeDetector.Impact.publicDeclarationChange();
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff, detector, noModules());

        IncrementalIndexPlan plan = planner.plan(publishedIndex(List.of("api/A.java")), "published", "selected",
                List.of("api/A.java", "api/B.java"));

        assertThat(plan.fullRepository()).isTrue();
        assertThat(plan.reanalyzePaths()).containsExactly("api/A.java", "api/B.java");
        assertThat(plan.copyPaths()).isEmpty();
        assertThat(plan.deletedPaths()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("impactCases")
    void should_plan_distinct_incremental_impact_classes(String name, ChangedSource change,
                                                          SourceContractChangeDetector.Impact impact,
                                                          ModuleLocator moduleLocator, List<String> sources,
                                                          List<String> expectedReanalyze, List<String> expectedCopy,
                                                          List<String> expectedDeleted, boolean expectedFull) {
        GitRevisionDiff diff = (publishedRevision, selectedRevision) -> List.of(change);
        SourceContractChangeDetector detector = (changedSource, declarations) -> impact;
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner(diff, detector, moduleLocator);

        IncrementalIndexPlan plan = planner.plan(new IncrementalIndexPlanner.PublishedIndex(
                sources, List.<SymbolDocument>of(), Map.of("api/Api.java", Set.of("app/Caller.java"),
                "app/Discovery.java", Set.of("app/Discovery.java"))),
                "published", "selected", selectedSources(sources, change));

        assertThat(plan.fullRepository()).isEqualTo(expectedFull);
        assertThat(plan.reanalyzePaths()).containsExactlyElementsOf(expectedReanalyze);
        assertThat(plan.copyPaths()).containsExactlyElementsOf(expectedCopy);
        assertThat(plan.deletedPaths()).containsExactlyElementsOf(expectedDeleted);
        assertThat(Collections.disjoint(plan.reanalyzePaths(), plan.copyPaths())).isTrue();
        assertThat(Collections.disjoint(plan.reanalyzePaths(), plan.deletedPaths())).isTrue();
        assertThat(Collections.disjoint(plan.copyPaths(), plan.deletedPaths())).isTrue();
    }

    private static Stream<Arguments> impactCases() {
        ModuleLocator noModules = noModules();
        ModuleLocator modules = new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) {
                return Optional.of("api");
            }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) {
                return Optional.of(Set.of("api", "app"));
            }

            @Override
            public Optional<Set<String>> supportedSources(String module) {
                return Optional.of(module.equals("api") ? Set.of("api/Api.java") : Set.of("app/Caller.java"));
            }
        };
        List<String> sources = List.of("api/Api.java", "api/RenamedApi.java", "app/Caller.java", "app/Discovery.java");
        return Stream.of(
                Arguments.of("body private", ChangedSource.modify("api/Api.java", "old", "new"),
                        SourceContractChangeDetector.Impact.bodyOrPrivateChange(), noModules, sources,
                        List.of("api/Api.java"), List.of("api/RenamedApi.java", "app/Caller.java", "app/Discovery.java"), List.of(), false),
                Arguments.of("public callers users", ChangedSource.modify("api/Api.java", "old", "new"),
                        SourceContractChangeDetector.Impact.publicDeclarationChange(), noModules, sources,
                        List.of("api/Api.java", "app/Caller.java"), List.of("api/RenamedApi.java", "app/Discovery.java"), List.of(), false),
                Arguments.of("inheritance implementations subclasses", ChangedSource.modify("api/Api.java", "old", "new"),
                        SourceContractChangeDetector.Impact.inheritanceChange(), noModules, sources,
                        List.of("api/Api.java", "app/Caller.java"), List.of("api/RenamedApi.java", "app/Discovery.java"), List.of(), false),
                Arguments.of("framework annotation discovery", ChangedSource.modify("api/Api.java", "old", "new"),
                        SourceContractChangeDetector.Impact.frameworkAnnotationChange(), noModules, sources,
                        List.of("api/Api.java", "app/Caller.java", "app/Discovery.java"), List.of("api/RenamedApi.java"), List.of(), false),
                Arguments.of("delete rename dependents", ChangedSource.rename("api/Api.java", "api/RenamedApi.java", "old", "new"),
                        SourceContractChangeDetector.Impact.publicDeclarationChange(), noModules, sources,
                        List.of("api/RenamedApi.java", "app/Caller.java"), List.of("app/Discovery.java"), List.of("api/Api.java"), false),
                Arguments.of("build closure", ChangedSource.modify("api/pom.xml", "old", "new"),
                        SourceContractChangeDetector.Impact.bodyOrPrivateChange(), modules, sources,
                        List.of("api/Api.java", "app/Caller.java"), List.of("api/RenamedApi.java", "app/Discovery.java"), List.of(), false),
                Arguments.of("uncertain", ChangedSource.modify("api/Api.java", "old", "new"),
                        SourceContractChangeDetector.Impact.uncertainChange(), noModules, sources,
                        List.of("api/Api.java", "api/RenamedApi.java", "app/Caller.java", "app/Discovery.java"), List.of(), List.of(), true));
    }

    private static IncrementalIndexPlanner.PublishedIndex publishedIndex(List<String> sources) {
        return new IncrementalIndexPlanner.PublishedIndex(sources, List.of(),
                Map.of("api/A.java", Set.of("app/Caller.java")));
    }

    private static ModuleLocator noModules() {
        return new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) {
                return Optional.empty();
            }

            @Override
            public Optional<Set<String>> supportedSources(String module) {
                return Optional.empty();
            }
        };
    }

    private static List<String> selectedSources(List<String> parentSources, ChangedSource change) {
        java.util.TreeSet<String> selected = new java.util.TreeSet<>(parentSources);
        if (change.kind() == ChangeKind.DELETE || change.kind() == ChangeKind.RENAME) {
            selected.remove(change.oldPath());
        }
        if (change.kind() == ChangeKind.ADD || change.kind() == ChangeKind.RENAME) {
            selected.add(change.newPath());
        }
        return List.copyOf(selected);
    }
}
