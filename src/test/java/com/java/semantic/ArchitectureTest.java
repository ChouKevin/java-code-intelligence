package com.java.semantic;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.syntax.application.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.ExactContentApplicationService;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_filesystem_types_out_of_dtos_when_api_layer_is_imported() {
        noClasses()
                .that().resideInAPackage("..api.dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.nio.file..", "java.io..", "java.net..")
                .as("no Path/File/URI may cross the HTTP API")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_require_explicit_monitoring_on_every_api_dto_record_component() {
        List<String> violations = new ArrayList<>();
        classes.stream()
                .map(JavaClass::getName)
                .filter(name -> name.startsWith("com.java.semantic.api.dto."))
                .map(ArchitectureTest::loadClass)
                .filter(Class::isRecord)
                .forEach(type -> {
                    for (RecordComponent component : type.getRecordComponents()) {
                        if (Objects.isNull(component.getAnnotation(ApiMonitoringField.class))) {
                            violations.add(type.getSimpleName() + "." + component.getName());
                        }
                    }
                });

        assertThat(violations).isEmpty();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("compiled architecture class is unavailable", exception);
        }
    }

    @Test
    void should_keep_the_domain_free_of_spring_web_when_domain_is_imported() {
        noClasses()
                .that().resideInAPackage("..repository.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "..api..")
                .as("the domain must not know about HTTP")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_controllers_off_adapters_when_api_is_imported() {
        noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..repository.adapter..")
                .as("controllers depend on application services, never on adapters")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_lsp4j_inside_the_jdtls_adapter_when_service_is_imported() {
        noClasses()
                .that().resideOutsideOfPackage("..semantic.adapter.jdtls..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.github.javaparser..", "org.eclipse.lsp4j..")
                .as("JavaParser and LSP4J types must not leak past the JDT LS adapter")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_confine_jdt_core_to_its_two_adapters_when_service_is_imported() {
        noClasses()
                .that().resideOutsideOfPackage("..semantic.adapter.jdtls..")
                .and().resideOutsideOfPackage("..syntax.adapter.jdt..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.jdt..")
                .as("JDT types live in the JDT LS adapter and the syntax adapter, nowhere else")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_the_syntax_domain_free_of_jdt_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("..syntax.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.jdt..", "org.eclipse.lsp4j..")
                .as("the syntax extraction results the API and callers consume must stay free of JDT types")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_canonical_method_declaration_resolution_neutral() {
        noClasses()
                .that().haveSimpleName("CanonicalMethodDeclarationResolver")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..semantic.application..",
                        "..semantic.adapter.jdtls..",
                        "..callgraph.application..",
                        "..api.dto..")
                .as("canonical declaration resolution must stay reusable from syntax consumers")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_the_semantic_domain_free_of_lsp4j_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("..semantic.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.lsp4j..", "org.eclipse.jdt..")
                .as("the JavaSemanticService contract and its domain records must stay free of LSP4J types")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_the_repository_layer_unaware_of_the_semantic_engine_when_service_is_imported() {
        noClasses()
                .that().resideInAPackage("com.java.semantic.repository..")
                .should().dependOnClassesThat().resideInAPackage("com.java.semantic.semantic..")
                .as("the semantic engine plugs into the repository layer through its port, never the reverse")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_lsp4j_out_of_the_workspace_lifecycle_contract_when_service_is_imported() {
        noClasses()
                .that().haveSimpleName("SemanticEngineStatus")
                .or().haveSimpleName("JdtWorkspaceManager")
                .should().dependOnClassesThat().resideInAnyPackage("org.eclipse.lsp4j..", "org.eclipse.jdt..")
                .as("the lifecycle contract Task 4 and the API consume must stay free of LSP4J types")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_expose_only_annotated_controllers_when_api_is_imported() {
        classes()
                .that().resideInAPackage("..api..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_callgraph_domain_and_application_free_of_runtime_adapters() {
        noClasses()
                .that().resideInAnyPackage("..callgraph.domain..", "..callgraph.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.eclipse.lsp4j..",
                        "org.eclipse.jdt..",
                        "org.eclipse.jgit..",
                        "..repository.adapter..")
                .as("call graph contracts and traversal depend only on service-local contracts, never runtime adapters")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_public_callgraph_and_semantic_contracts_free_of_filesystem_types() {
        noClasses()
                .that().resideInAnyPackage(
                        "..callgraph.domain..",
                        "..callgraph.application..",
                        "..semantic.domain..",
                        "..semantic.application..")
                .and().resideOutsideOfPackage("..repository.domain..")
                .and().doNotHaveSimpleName("SemanticCallGraphBuilder")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..", "java.net..", "java.nio.file..")
                .as("public Task 7 contracts must not expose filesystem paths or URI objects; RepositorySnapshot remains an internal repository contract")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_semantic_application_away_from_repository_adapters_and_api() {
        noClasses()
                .that().resideInAPackage("..semantic.application..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository.adapter..", "..repository.config..",
                        "..repository.port..", "..api..")
                .as("semantic orchestration may use repository application/domain contracts, not adapters or HTTP")
                .allowEmptyShould(false)
                .check(classes);
        classes()
                .that().resideInAPackage("..semantic.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.java.semantic.semantic.application..",
                        "com.java.semantic.semantic.domain..",
                        "com.java.semantic.callgraph..",
                        "com.java.semantic.syntax.domain..",
                        "com.java.semantic.diagnostic..",
                        "com.java.semantic.identity..",
                        "com.java.semantic.config..",
                        "com.java.semantic.repository.application..",
                        "com.java.semantic.repository.domain..",
                        "java..",
                        "lombok..",
                        "org.slf4j..",
                        "org.springframework..")
                .as("semantic application may orchestrate repository application/domain contracts with safe diagnostics")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_agent_packages_out_of_semantic_service_production_code() {
        noClasses()
                .that().resideInAPackage("com.java.semantic..")
                .should().dependOnClassesThat().resideInAPackage("com.java.system.agent..")
                .as("the semantic service and agent remain independent Maven applications")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_api_away_from_jdtls_adapter_and_protocol_libraries() {
        noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..semantic.adapter.jdtls..",
                        "org.eclipse.lsp4j..",
                        "org.eclipse.jdt..",
                        "org.eclipse.jgit..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_jdtls_adapter_types_inside_adapter_and_composition_root() {
        noClasses()
                .that().resideOutsideOfPackage("..semantic.adapter.jdtls..")
                .and().resideOutsideOfPackage("..config..")
                .should().dependOnClassesThat()
                .resideInAPackage("..semantic.adapter.jdtls..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_repository_layer_away_from_trie_implementation() {
        noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..trie..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_enter_analysis_and_entry_point_content_through_snapshot_gateway() {
        classes()
                .that().haveSimpleName("SemanticAnalysisApplicationService")
                .or().haveSimpleName("EntryPointDiscoveryApplicationService")
                .or().haveSimpleName("EventListenerDiscoveryApplicationService")
                .or().haveSimpleName("MethodImplementationDiscoveryApplicationService")
                .or().areAssignableTo(ConceptDiscoveryApplicationService.class)
                .or().areAssignableTo(ExactContentApplicationService.class)
                .or().areAssignableTo(TypeMemberDiscoveryApplicationService.class)
                .should().callMethod(
                        RepositoryApplicationService.class,
                        "withSnapshot",
                        RepositoryId.class,
                        Optional.class,
                        Function.class)
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_public_task_eight_dtos_free_of_runtime_and_filesystem_types() {
        noClasses()
                .that().resideInAPackage("..api.dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..",
                        "java.net..",
                        "java.nio.file..",
                        "org.eclipse.lsp4j..",
                        "org.eclipse.jdt..",
                        "org.eclipse.jgit..",
                        "..repository.adapter..",
                        "..semantic.adapter.jdtls..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_outgoing_graph_response_dtos_free_of_domain_and_runtime_types() {
        noClasses()
                .that().haveSimpleName("PositionResponse")
                .or().haveSimpleName("SourceRangeResponse")
                .or().haveSimpleName("GraphTraversalResponse")
                .or().haveSimpleName("GraphNodeResponse")
                .or().haveSimpleName("GraphEdgeResponse")
                .or().haveSimpleName("GraphWarningResponse")
                .or().haveSimpleName("GraphErrorResponse")
                .or().haveSimpleName("OutgoingCallGraphResponse")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.java.semantic.identity..",
                        "com.java.semantic.repository..",
                        "com.java.semantic.semantic..",
                        "com.java.semantic.callgraph..",
                        "java.io..",
                        "java.net..",
                        "java.nio.file..",
                        "org.eclipse.lsp4j..",
                        "org.eclipse.jdt..",
                        "org.eclipse.jgit..")
                .as("outgoing graph response DTOs must remain API-only wire values")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_http_controllers_inside_api_package() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..api..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_application_and_callgraph_types_free_of_web_annotations() {
        noClasses()
                .that().resideInAnyPackage(
                        "..callgraph..",
                        "..semantic.application..",
                        "..syntax.application..",
                        "..trie..",
                        "..repository.application..")
                .should().beAnnotatedWith(Controller.class)
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().resideInAnyPackage(
                        "..callgraph..",
                        "..semantic.application..",
                        "..syntax.application..",
                        "..trie..",
                        "..repository.application..")
                .should().beAnnotatedWith(RestController.class)
                .allowEmptyShould(false)
                .check(classes);
    }
}
