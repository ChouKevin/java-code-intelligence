package com.java.semantic.indexer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Indexer owns writes only; Query owns all read HTTP, MCP, and monitoring transports. */
@AnalyzeClasses(packages = {"com.java.semantic.indexer", "com.java.semantic.query"}, importOptions = ImportOption.DoNotIncludeTests.class)
class IndexerArchitectureTest {
    @ArchTest
    static final ArchRule indexer_does_not_depend_on_query_read_transports = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage("com.java.semantic.api..", "com.java.semantic.mcp..",
                    "com.java.semantic.monitoring..");

    @ArchTest
    static final ArchRule query_does_not_depend_on_indexer_runtime_or_jgit_or_jdt = noClasses()
            .that().resideInAnyPackage("com.java.semantic.query..")
            .should().dependOnClassesThat().resideInAnyPackage("com.java.semantic.indexer..", "org.eclipse.jgit..", "org.eclipse.jdt..");

    @org.junit.jupiter.api.Test
    void indexer_production_sources_do_not_offer_a_fixture_mode() throws IOException {
        Path productionSources = Path.of("src/main/java/com/java/semantic/indexer");
        try (Stream<Path> paths = Files.walk(productionSources)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(IndexerArchitectureTest::read)
                    .filter(source -> source.contains("fixtures/uat") || source.contains("fixture-mode")
                            || source.contains("fixtureMode")))
                    .as("fixture mode must remain test-only")
                    .isEmpty();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError("cannot inspect production source " + path, exception);
        }
    }
}
