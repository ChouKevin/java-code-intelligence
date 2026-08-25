package com.java.semantic.indexer.incremental;

import com.java.semantic.model.index.SymbolDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalIndexPlannerTest {

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
                "published", "selected");

        assertThat(plan.fullRepository()).isEqualTo(expectedFull);
        assertThat(plan.reanalyzePaths()).containsExactlyElementsOf(expectedReanalyze);
        assertThat(plan.copyPaths()).containsExactlyElementsOf(expectedCopy);
        assertThat(plan.deletedPaths()).containsExactlyElementsOf(expectedDeleted);
        assertThat(Collections.disjoint(plan.reanalyzePaths(), plan.copyPaths())).isTrue();
        assertThat(Collections.disjoint(plan.reanalyzePaths(), plan.deletedPaths())).isTrue();
        assertThat(Collections.disjoint(plan.copyPaths(), plan.deletedPaths())).isTrue();
    }

    private static Stream<Arguments> impactCases() {
        ModuleLocator noModules = new ModuleLocator() {
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
}
