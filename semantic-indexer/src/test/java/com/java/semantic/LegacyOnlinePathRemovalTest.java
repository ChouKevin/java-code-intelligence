package com.java.semantic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyOnlinePathRemovalTest {

    @Test
    void no_longer_packages_the_single_application_or_online_read_path() {
        assertClassIsAbsent("com.java.semantic.SemanticServiceApplication");
        assertClassIsAbsent("com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService");
        assertClassIsAbsent("com.java.semantic.syntax.adapter.cache.CaffeineRevisionBoundRepositorySyntaxProvider");
        assertClassIsAbsent("com.java.semantic.semantic.adapter.cache.CaffeineInternalReferenceAnalysisCache");
    }

    private static void assertClassIsAbsent(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }
}
