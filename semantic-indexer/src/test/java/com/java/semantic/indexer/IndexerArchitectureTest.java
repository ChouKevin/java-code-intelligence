package com.java.semantic.indexer;

import com.java.semantic.SemanticIndexerApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Inspect only this module's compiled production origin; never Query or test classpath classes. */
class IndexerArchitectureTest {
    @Test
    void private_source_process_does_not_depend_on_query_read_transports_or_legacy_backends() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "com.java.semantic.query..",
                "com.java.semantic.api..",
                "com.java.semantic.mcp..",
                "com.java.semantic.monitoring..",
                "org.springframework.data.mongodb..",
                "com.mongodb..",
                "org.bson..",
                "org.eclipse.jdt..",
                "org.eclipse.lsp4j..")
                .check(new ClassFileImporter().importUrl(
                        SemanticIndexerApplication.class.getProtectionDomain().getCodeSource().getLocation()));
    }
}
