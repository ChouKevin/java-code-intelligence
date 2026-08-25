package com.java.semantic.query;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.java.semantic.query", importOptions = ImportOption.DoNotIncludeTests.class)
class QueryArchitectureTest {

    @ArchTest
    static final ArchRule query_does_not_depend_on_indexer_or_language_server = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage(
                    "com.java.semantic.indexer..", "com.java.semantic.semantic..", "com.java.semantic.syntax..",
                    "org.eclipse.jgit..", "org.eclipse.lsp4j..", "org.eclipse.jdt..", "com.github.benmanes.caffeine..");
}
