package com.java.semantic.query;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SourceContentBoundaryTest {

    static final ArchRule only_query_application_and_root_wiring_depend_on_current_source_query_service = noClasses()
            .that().resideOutsideOfPackage("com.java.semantic.query.application")
            .and().doNotHaveFullyQualifiedName("com.java.semantic.query.SemanticQueryApplication")
            .should().dependOnClassesThat().haveSimpleName("CurrentSourceQueryService");

    @Test
    void source_content_access_is_limited_to_query_application_and_root_wiring() {
        only_query_application_and_root_wiring_depend_on_current_source_query_service.check(queryProductionClasses());
    }

    private static JavaClasses queryProductionClasses() {
        return new ClassFileImporter().importUrl(
                SemanticQueryApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
