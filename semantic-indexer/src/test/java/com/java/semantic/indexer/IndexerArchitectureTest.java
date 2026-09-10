package com.java.semantic.indexer;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Indexer owns writes only; Query owns all read HTTP, MCP, and monitoring transports. */
class IndexerArchitectureTest {

    static final ArchRule indexer_does_not_depend_on_query_read_transports = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage("com.java.semantic.query..", "com.java.semantic.api..",
                    "com.java.semantic.mcp..", "com.java.semantic.monitoring..");

    @Test
    void indexer_module_origins_include_all_feature_roots_but_not_query_test_classpath() {
        JavaClasses indexerClasses = indexerProductionClasses();

        assertThat(indexerClasses).extracting(JavaClass::getName)
                .contains("com.java.semantic.SemanticIndexerApplication", "com.java.semantic.callgraph.application.SemanticCallGraphBuilder",
                        "com.java.semantic.repository.application.RepositoryApplicationService",
                        "com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService")
                .doesNotContain("com.java.semantic.indexer.IndexerArchitectureTest",
                        "com.java.semantic.query.application.SemanticQueryFacade", "com.java.semantic.model.repository.RepositoryId");
    }

    @Test
    void indexer_does_not_depend_on_query_read_transports() {
        indexer_does_not_depend_on_query_read_transports.check(indexerProductionClasses());
    }

    @Test
    void query_dependency_is_rejected() {
        JavaClasses probe = new ClassFileImporter().importClasses(ForbiddenQueryDependency.class);

        assertThat(indexer_does_not_depend_on_query_read_transports.evaluate(probe).hasViolation()).isTrue();
    }

    @Test
    void indexer_production_sources_do_not_offer_a_fixture_mode() throws IOException {
        Path productionSources = Path.of("src/main/java/com/java/semantic");
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

    private static JavaClasses indexerProductionClasses() {
        return new ClassFileImporter().importUrl(
                SemanticIndexerApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static final class ForbiddenQueryDependency {
        private SemanticQueryFacade facade;
    }
}
