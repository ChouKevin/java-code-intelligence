package com.java.semantic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticIndexerApplicationTest {

    @Test
    void recognizes_only_the_explicit_schema_bootstrap_command() {
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{"--semantic.schema-bootstrap=true"})).isTrue();
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{"--semantic.schema-bootstrap=false"})).isFalse();
        assertThat(SemanticIndexerApplication.schemaBootstrapRequested(new String[]{})).isFalse();
    }

    @Test
    void packages_the_metrics_auto_configuration_required_by_the_indexer() throws IOException {
        Path modulePom = Path.of(System.getProperty("basedir"), "pom.xml");

        assertThat(Files.readString(modulePom))
                .contains("<artifactId>spring-boot-starter-micrometer-metrics</artifactId>");
    }
}
