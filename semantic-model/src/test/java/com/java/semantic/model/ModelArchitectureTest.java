package com.java.semantic.model;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.java.semantic.model", importOptions = ImportOption.DoNotIncludeTests.class)
class ModelArchitectureTest {

    @ArchTest
    static final ArchRule main_model_code_depends_only_on_jdk_or_model_packages = noClasses()
            .that().resideInAnyPackage("com.java.semantic.model..")
            .should().dependOnClassesThat().resideOutsideOfPackages("java..", "com.java.semantic.model..");
}
