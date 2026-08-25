package com.java.semantic.indexer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Indexer owns writes only; Query owns all read HTTP, MCP, and monitoring transports. */
@AnalyzeClasses(packages = "com.java.semantic.indexer", importOptions = ImportOption.DoNotIncludeTests.class)
class IndexerArchitectureTest {
    @ArchTest
    static final ArchRule indexer_does_not_depend_on_query_read_transports = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage("com.java.semantic.api..", "com.java.semantic.mcp..",
                    "com.java.semantic.monitoring..");
}
