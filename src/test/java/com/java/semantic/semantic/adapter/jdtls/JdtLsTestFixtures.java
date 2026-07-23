package com.java.semantic.semantic.adapter.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class JdtLsTestFixtures {

    private JdtLsTestFixtures() {
    }

    static Path createFakeHome(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path home = root.resolve("jdtls");
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("config_linux"));
        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_test.jar"));
        return home;
    }
}
