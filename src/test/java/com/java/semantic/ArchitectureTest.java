package com.java.semantic;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.java.semantic");
    }

    @Test
    void should_keep_jgit_inside_the_adapter_when_repository_layer_is_imported() {
        noClasses()
                .that().resideOutsideOfPackage("..repository.adapter.jgit..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.jgit..")
                .as("JGit must not leak past the adapter; the domain receives snapshots, not Git objects")
                .check(classes);
    }

    @Test
    void should_keep_filesystem_types_out_of_dtos_when_api_layer_is_imported() {
        noClasses()
                .that().resideInAPackage("..api.dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.nio.file..", "java.io..", "java.net..")
                .as("no Path/File/URI may cross the HTTP API")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_the_domain_free_of_spring_web_when_domain_is_imported() {
        noClasses()
                .that().resideInAPackage("..repository.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "..api..")
                .as("the domain must not know about HTTP")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_controllers_off_adapters_when_api_is_imported() {
        noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..repository.adapter..")
                .as("controllers depend on application services, never on adapters")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_lsp4j_inside_the_jdtls_adapter_when_service_is_imported() {
        noClasses()
                .that().resideOutsideOfPackage("..semantic.adapter.jdtls..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.github.javaparser..", "org.eclipse.lsp4j..")
                .as("JavaParser and LSP4J types must not leak past the JDT LS adapter")
                .check(classes);
    }

    @Test
    void should_confine_jdt_core_to_its_two_adapters_when_service_is_imported() {
        noClasses()
                .that().resideOutsideOfPackage("..semantic.adapter.jdtls..")
                .and().resideOutsideOfPackage("..syntax.adapter.jdt..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.jdt..")
                .as("JDT types live in the JDT LS adapter and the syntax adapter, nowhere else")
                .check(classes);
    }

    @Test
    void should_keep_the_syntax_domain_free_of_jdt_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("..syntax.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.jdt..", "org.eclipse.lsp4j..")
                .as("the syntax extraction results the API and callers consume must stay free of JDT types")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_the_semantic_domain_free_of_lsp4j_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("..semantic.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.lsp4j..", "org.eclipse.jdt..")
                .as("the JavaSemanticService contract and its domain records must stay free of LSP4J types")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_the_repository_layer_unaware_of_the_semantic_engine_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("com.java.semantic.repository..")
                .should().dependOnClassesThat().resideInAPackage("com.java.semantic.semantic..")
                .as("the semantic engine plugs into the repository layer through its port, never the reverse")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void should_keep_lsp4j_out_of_the_workspace_lifecycle_contract_when_service_is_imported() {
        noClasses()
                .that().haveSimpleName("SemanticEngineStatus")
                .or().haveSimpleName("JdtWorkspaceManager")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.lsp4j..", "org.eclipse.jdt..")
                .as("the lifecycle contract Task 4 and the API consume must stay free of LSP4J types")
                .check(classes);
    }

    @Test
    void should_expose_only_annotated_controllers_when_api_is_imported() {
        classes()
                .that().resideInAPackage("..api..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .allowEmptyShould(true)
                .check(classes);
    }
}
