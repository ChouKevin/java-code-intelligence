package com.java.semantic;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.JavaSourceIdentityHttpMapper;
import com.java.semantic.api.MapperIdentityHttpMapper;
import com.java.semantic.api.SourceLocationHttpMapper;
import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.identity.MapperFragmentIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.config.SemanticAnalysisConfiguration;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.EvidenceSourceApplicationService;
import com.java.semantic.syntax.application.SourceSymbolResolutionApplicationService;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.adapter.cache.CaffeineRevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 Java Semantic Service 的分層依賴與 HTTP 合約邊界 */
class ArchitectureTest {

    private static final String SYNTAX_APPLICATION_PACKAGE = "com.java.semantic.syntax.application";

    private static final Set<String> SOURCE_SYMBOL_APPLICATION_CONTRACT_NAMES = Set.of(
            "SourceSymbolCandidate",
            "VariableLike",
            "StaticConstant",
            "Method",
            "SourceType",
            "SourceContextCandidate",
            "SourceTypeContextCandidate",
            "SourceMethodContextCandidate",
            "SourceSymbolResolution",
            "SourceSymbolResolutionQuery",
            "SourceSymbolResolver",
            "SourceSymbolContext",
            "SourceSymbolIssueCode",
            "SourceSymbolIssueSummary",
            "SourceSymbolKind",
            "SourceSymbolResolutionStatus",
            "SourceContextCandidateLimits",
            "NavigableSourceSymbolCandidate",
            "NavigableSourceContextCandidate",
            "RevisionBoundSourceSymbolResolution");

    private static final DescribedPredicate<JavaClass> SOURCE_SYMBOL_APPLICATION_CONTRACT =
            new DescribedPredicate<>("source-symbol application contract") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return SYNTAX_APPLICATION_PACKAGE.equals(javaClass.getPackageName())
                            && SOURCE_SYMBOL_APPLICATION_CONTRACT_NAMES.contains(javaClass.getSimpleName());
                }
            };

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
    void should_keep_the_source_identity_model_jdk_only() {
        noClasses()
                .that().resideInAPackage("com.java.semantic.model..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "com.java.semantic.model..")
                .as("the source identity model must remain JDK-only")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_shared_http_payloads_inside_the_transport_boundary() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.java.semantic.api.dto.identity..",
                        "com.java.semantic.api.dto.location..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "java..",
                        "com.fasterxml.jackson.annotation..",
                        "jakarta.validation..",
                        "com.java.semantic.api.dto.identity..",
                        "com.java.semantic.api.dto.location..",
                        "com.java.semantic.monitoring..")
                .as("shared HTTP payloads may depend only on transport concerns, neutral monitoring metadata, and other shared payloads")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_source_member_identity_in_the_syntax_domain() {
        classes()
                .that().haveSimpleName("SourceMemberIdentity")
                .should().resideInAPackage("..syntax.domain..")
                .as("source member identity belongs to syntax-domain source semantics")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_legacy_identity_types_and_flat_constructors_absent() {
        List<String> classNames = classes.stream().map(JavaClass::getName).toList();
        assertThat(classNames).doesNotContain(
                "com.java.semantic.syntax.domain.ClassMetadata",
                "com.java.semantic.syntax.domain.ResolvedTypeIdentity",
                "com.java.semantic.model.codefact.PolicyIdentity",
                "com.java.semantic.model.codefact.SourceMemberIdentity",
                "com.java.semantic.api.dto.MethodTargetRequest",
                "com.java.semantic.api.dto.MethodTargetResponse",
                "com.java.semantic.api.dto.JavaTypeIdentityResponse",
                "com.java.semantic.api.dto.PositionResponse",
                "com.java.semantic.api.dto.SourceRangeResponse",
                "com.java.semantic.api.dto.MapperStatementIdentityResponse",
                "com.java.semantic.api.dto.MapperFragmentIdentityRequest",
                "com.java.semantic.api.dto.MapperFragmentIdentityResponse",
                "com.java.semantic.api.dto.SourceSymbolPositionRequest",
                "com.java.semantic.api.dto.SourceSymbolContextRequest",
                "com.java.semantic.api.dto.SourceSymbolMethodContextRequest",
                "com.java.semantic.api.dto.ConceptIdentityResponse$MapperStatementKeyResponse",
                "com.java.semantic.api.dto.ConceptIdentityResponse$MapperStatementVariantResponse",
                "com.java.semantic.api.dto.DiscoveryFollowUpResponse$SourceSymbolContextResponse",
                "com.java.semantic.api.dto.DiscoveryFollowUpResponse$SourceSymbolMethodContextResponse");

        assertThat(loadClass("com.java.semantic.model.codefact.MethodTarget").getConstructors())
                .noneMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{
                        String.class, String.class, String.class, String.class, List.class}));
        assertThat(loadClass("com.java.semantic.model.codefact.SourceTypeIdentity").getConstructors())
                .noneMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{
                        String.class, Optional.class}));
    }

    @Test
    void should_construct_java_source_identity_payloads_only_through_the_shared_http_mapper() {
        assertPayloadConstructionOwner(
                JavaSourceIdentityHttpMapper.class,
                Set.of(
                        JavaTypeIdentityPayload.class,
                        SourceTypeIdentityPayload.class,
                        MethodTargetPayload.class,
                        SourceMemberIdentityPayload.TypeMember.class,
                        SourceMemberIdentityPayload.MethodScoped.class));
    }

    @Test
    void should_construct_location_payloads_only_through_the_shared_http_mapper() {
        assertPayloadConstructionOwner(
                SourceLocationHttpMapper.class,
                Set.of(PositionPayload.class, TextRangePayload.class, SourceRangePayload.class));
    }

    @Test
    void should_construct_mapper_identity_payloads_only_through_the_shared_http_mapper() {
        assertPayloadConstructionOwner(
                MapperIdentityHttpMapper.class,
                Set.of(
                        MapperStatementKeyPayload.class,
                        MapperStatementIdentityPayload.class,
                        MapperFragmentIdentityPayload.class));
    }

    @Test
    void should_require_explicit_monitoring_on_every_transport_dto_record_component() {
        List<String> violations = new ArrayList<>();
        classes.stream()
                .map(JavaClass::getName)
                .filter(name -> name.startsWith("com.java.semantic.api.dto.")
                        || name.startsWith("com.java.semantic.mcp.dto."))
                .map(ArchitectureTest::loadClass)
                .filter(Class::isRecord)
                .forEach(type -> {
                    for (RecordComponent component : type.getRecordComponents()) {
                        if (Objects.isNull(component.getAnnotation(MonitoringField.class))) {
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

    private static void assertPayloadConstructionOwner(
            Class<?> owner,
            Set<Class<?>> payloadTypes) {
        Set<String> payloadTypeNames = payloadTypes.stream()
                .map(Class::getName)
                .collect(Collectors.toSet());
        List<String> violations = classes.stream()
                .filter(javaClass -> !javaClass.isEquivalentTo(owner))
                .flatMap(javaClass -> javaClass.getConstructorCallsFromSelf().stream()
                        .filter(call -> payloadTypeNames.contains(call.getTargetOwner().getName()))
                        .map(call -> javaClass.getName() + " -> " + call.getTargetOwner().getName()))
                .toList();

        assertThat(violations).isEmpty();
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
    void should_keep_caffeine_and_cache_adapters_behind_the_cache_adapter_boundary() {
        classes()
                .that().haveSimpleName("InternalSourceReferenceApplicationService")
                .should().dependOnClassesThat().haveSimpleName("InternalReferenceAnalysisCache")
                .as("internal-reference orchestration must use only the application cache abstraction")
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().resideOutsideOfPackage("..adapter.cache..")
                .and().resideOutsideOfPackage("..config..")
                .should().dependOnClassesThat().resideInAnyPackage("com.github.benmanes.caffeine..")
                .as("Caffeine types belong only to cache adapters and the composition root")
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().resideOutsideOfPackage("..adapter.cache..")
                .and().resideOutsideOfPackage("..config..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.cache..")
                .as("cache adapter types belong only to cache adapters and the composition root")
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().resideInAPackage("..semantic.application..")
                .should().dependOnClassesThat().resideInAnyPackage("com.github.benmanes.caffeine..")
                .as("semantic application contracts and orchestration must remain Caffeine-free")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_limit_syntax_extraction_to_revision_cache_loading_boundaries() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(
                        CaffeineRevisionBoundRepositorySyntaxProvider.class.getName())
                .and().doNotHaveFullyQualifiedName(JdtSyntaxExtractionService.class.getName())
                .and().doNotHaveFullyQualifiedName(SemanticAnalysisConfiguration.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        SyntaxExtractionService.class.getName())
                .as("production syntax consumers must use the revision-bound syntax provider")
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
    void should_keep_repository_source_containment_free_of_jdt_lsp_and_web_types() {
        noClasses()
                .that().haveSimpleNameStartingWith("RepositorySourceContainment")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.eclipse.jdt..",
                        "org.eclipse.lsp4j..",
                        "org.springframework.web..",
                        "com.java.semantic.api..")
                .as("repository source containment must remain independent of parsers, protocols, and HTTP")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_share_source_declaration_matching_without_coupling_the_facades() {
        classes()
                .that().haveSimpleName("JdtSourceSymbolResolver")
                .or().haveSimpleName("JdtExactSourceDeclarationResolver")
                .should().dependOnClassesThat().haveSimpleName("JdtSourceDeclarationLocator")
                .as("both JDT resolution facades must reuse the shared declaration locator")
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().haveSimpleName("JdtSourceSymbolResolver")
                .should().dependOnClassesThat().haveSimpleName("JdtExactSourceDeclarationResolver")
                .as("source-symbol resolution must not depend on exact-declaration resolution")
                .allowEmptyShould(false)
                .check(classes);
        noClasses()
                .that().haveSimpleName("JdtExactSourceDeclarationResolver")
                .should().dependOnClassesThat().haveSimpleName("JdtSourceSymbolResolver")
                .as("exact-declaration resolution must not depend on source-symbol resolution")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_confine_source_declaration_matching_helpers_to_the_jdt_syntax_adapter() {
        noClasses()
                .that().resideOutsideOfPackage("..syntax.adapter.jdt..")
                .should().dependOnClassesThat()
                .haveNameMatching("com\\.java\\.semantic\\.syntax\\.adapter\\.jdt\\.JdtSourceDeclarationLocator(\\$.*)?")
                .as("source-declaration matching helpers must remain private to the JDT syntax adapter")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_the_syntax_domain_free_of_adapter_and_http_dependencies() {
        noClasses()
                .that().resideInAPackage("..syntax.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.eclipse.jdt..",
                        "org.eclipse.lsp4j..",
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "com.java.semantic.api..")
                .as("syntax-domain values must remain free of adapter, HTTP, JSON, and Spring dependencies")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_concept_discovery_free_of_http_runtime_adapters_and_sibling_services() {
        noClasses()
                .that().resideInAPackage("..syntax.application.concept..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..api..",
                        "com.fasterxml.jackson..",
                        "org.springframework.web..",
                        "org.springdoc..",
                        "io.swagger..",
                        "..callgraph..",
                        "..semantic.adapter..",
                        "..syntax.adapter.jdt..")
                .as("concept discovery composes syntax and repository contracts without HTTP, mappers, or runtime adapters")
                .allowEmptyShould(false)
                .check(classes);

        List<String> siblingApplicationDependencies = classes.stream()
                .filter(javaClass -> javaClass.getPackageName()
                        .startsWith("com.java.semantic.syntax.application.concept"))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .map(Dependency::getTargetClass)
                .filter(target -> target.getPackageName().startsWith("com.java.semantic.syntax.application"))
                .filter(target -> !target.getPackageName()
                        .startsWith("com.java.semantic.syntax.application.concept"))
                .map(JavaClass::getName)
                .toList();
        assertThat(siblingApplicationDependencies).isEmpty();
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
                        "com.java.semantic.model.codefact..",
                        "com.java.semantic.model.repository..",
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
                .or().haveSimpleName("InternalSourceReferenceApplicationService")
                .or().haveSimpleName("SourceSegmentApplicationService")
                .or().haveSimpleName("MethodSourceApplicationService")
                .or().haveSimpleName("ApiRouteApplicationService")
                .or().areAssignableTo(ConceptDiscoveryApplicationService.class)
                .or().areAssignableTo(EvidenceSourceApplicationService.class)
                .or().areAssignableTo(SourceSymbolResolutionApplicationService.class)
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
    void should_confine_source_symbol_parser_types_to_the_jdt_syntax_adapter() {
        noClasses()
                .that().resideOutsideOfPackage("..syntax.adapter.jdt..")
                .should().dependOnClassesThat().haveSimpleName("JdtParseContext")
                .as("the request-scoped parse partition must remain private to the JDT syntax adapter")
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void should_keep_source_symbol_application_contract_framework_neutral() {
        noClasses()
                .that(SOURCE_SYMBOL_APPLICATION_CONTRACT)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.eclipse.jdt..",
                        "org.springframework.web..",
                        "com.fasterxml.jackson..",
                        "java.nio.file..",
                        "com.java.semantic.api..")
                .as("source-symbol application contracts must not expose JDT, HTTP, JSON, filesystem, or API types")
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
                .that().haveSimpleName("GraphTraversalResponse")
                .or().haveSimpleName("GraphNodeResponse")
                .or().haveSimpleName("GraphEdgeResponse")
                .or().haveSimpleName("GraphWarningResponse")
                .or().haveSimpleName("GraphErrorResponse")
                .or().haveSimpleName("OutgoingCallGraphResponse")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.java.semantic.model.codefact..",
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
