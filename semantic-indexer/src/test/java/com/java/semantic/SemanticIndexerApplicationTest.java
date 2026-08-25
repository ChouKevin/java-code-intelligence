package com.java.semantic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticIndexerApplicationTest {

    @Test
    void recognizes_only_the_explicit_schema_bootstrap_command() {
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{"--semantic.schema-bootstrap=true"})).isTrue();
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{"--semantic.schema-bootstrap=false"})).isFalse();
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{})).isFalse();
    }
}
