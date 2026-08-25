package com.java.semantic.query;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.java.semantic.query", importOptions = ImportOption.DoNotIncludeTests.class)
class SourceContentBoundaryTest {

    @ArchTest
    static final ArchRule only_source_tool_service_reads_stored_source_content = noClasses()
            .that().resideOutsideOfPackage("com.java.semantic.query.application")
            .and().doNotHaveFullyQualifiedName("com.java.semantic.query.SemanticQueryApplication")
            .should().dependOnClassesThat().haveSimpleName("CurrentSourceQueryService");
}
