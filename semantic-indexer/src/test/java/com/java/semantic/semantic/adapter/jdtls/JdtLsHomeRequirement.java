package com.java.semantic.semantic.adapter.jdtls;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;

public final class JdtLsHomeRequirement {

    private JdtLsHomeRequirement() {
    }

    public static Path requireHome(String configuredHome) {
        assertThat(StringUtils.hasText(configuredHome))
                .as("JDTLS_HOME must be configured for real JDT LS integration tests")
                .isTrue();
        Path home = Path.of(configuredHome);
        assertThat(Files.isDirectory(home))
                .as("JDTLS_HOME must point at an installed JDT LS directory")
                .isTrue();
        return home;
    }
}
