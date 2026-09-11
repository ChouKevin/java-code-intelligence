package com.java.semantic.semantic.adapter.jdtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

class JdtLsHomeRequirementTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejects_missing_and_blank_configuration_with_a_clear_failure() {
        assertMissingConfiguration(null);
        assertMissingConfiguration("");
        assertMissingConfiguration("   ");
    }

    @Test
    void rejects_a_non_directory_with_a_clear_failure_without_the_path() throws IOException {
        Path missingHome = temporaryDirectory.resolve("missing-jdtls-home");
        Path fileHome = Files.createFile(temporaryDirectory.resolve("jdtls-home-file"));

        assertInvalidHome(missingHome);
        assertInvalidHome(fileHome);
    }

    @Test
    void returns_a_valid_directory_without_validating_launcher_contents() throws IOException {
        Path home = Files.createDirectory(temporaryDirectory.resolve("jdtls-home"));

        assertThat(JdtLsHomeRequirement.requireHome(home.toString())).isEqualTo(home);
    }

    private void assertMissingConfiguration(String configuredHome) {
        assertThatThrownBy(() -> JdtLsHomeRequirement.requireHome(configuredHome))
                .isInstanceOf(AssertionError.class)
                .isNotInstanceOf(TestAbortedException.class)
                .hasMessageContaining("JDTLS_HOME must be configured for real JDT LS integration tests");
    }

    private void assertInvalidHome(Path home) {
        assertThatThrownBy(() -> JdtLsHomeRequirement.requireHome(home.toString()))
                .isInstanceOf(AssertionError.class)
                .isNotInstanceOf(TestAbortedException.class)
                .hasMessageContaining("JDTLS_HOME must point at an installed JDT LS directory")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(home.toString()));
    }
}
