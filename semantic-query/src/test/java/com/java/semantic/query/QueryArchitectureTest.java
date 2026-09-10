package com.java.semantic.query;

import com.java.semantic.IndexerRootRuntimeStandIn;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class QueryArchitectureTest {

    static final ArchRule query_does_not_depend_on_indexer_or_language_server = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage(
                    "com.java.semantic", "com.java.semantic.indexer..", "com.java.semantic.semantic..",
                    "com.java.semantic.syntax..",
                    "com.java.semantic.repository..", "com.java.semantic.callgraph..", "com.java.semantic.config..",
                    "com.java.semantic.diagnostic..",
                    "org.eclipse.jgit..", "org.eclipse.lsp4j..", "org.eclipse.jdt..", "com.github.benmanes.caffeine..");

    static final ArchRule application_does_not_depend_on_transport = noClasses()
            .that().resideInAnyPackage("com.java.semantic.query.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.java.semantic.api..", "com.java.semantic.mcp..", "io.modelcontextprotocol..", "org.springframework.ai..");

    @Test
    void query_module_origins_include_transport_feature_roots_but_not_test_or_other_module_classes() {
        JavaClasses queryClasses = queryProductionClasses();

        assertThat(queryClasses).extracting(JavaClass::getName)
                .contains("com.java.semantic.api.SemanticQueryController", "com.java.semantic.mcp.SemanticMcpToolCatalog",
                        "com.java.semantic.query.application.SourceSliceService")
                .doesNotContain("com.java.semantic.query.QueryArchitectureTest", "com.java.semantic.model.repository.RepositoryId");
    }

    @Test
    void query_does_not_depend_on_indexer_or_language_server() {
        query_does_not_depend_on_indexer_or_language_server.check(queryProductionClasses());
    }

    @Test
    void application_does_not_depend_on_transport() {
        application_does_not_depend_on_transport.check(queryProductionClasses());
    }

    @Test
    void indexer_root_runtime_dependency_is_rejected() {
        JavaClasses probe = new ClassFileImporter().importClasses(QueryArchitectureTest.class,
                IndexerRootRuntimeStandIn.class);

        assertThat(query_does_not_depend_on_indexer_or_language_server.evaluate(probe).hasViolation()).isTrue();
    }

    private static JavaClasses queryProductionClasses() {
        return new ClassFileImporter().importUrl(
                SemanticQueryApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private IndexerRootRuntimeStandIn indexerRootRuntime;
}
