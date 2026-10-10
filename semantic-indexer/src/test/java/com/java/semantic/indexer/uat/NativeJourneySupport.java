package com.java.semantic.indexer.uat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared owned-process lifecycle for the two packaged native journeys. */
final class NativeJourneySupport {
    private NativeJourneySupport() { }
    static void stop(Process process) throws Exception {
        if (Objects.isNull(process)) {
            return;
        }
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String base(int port) {
        return "http://127.0.0.1:" + port;
    }

    /** Compile one service-owned dependency, never source from the target Git fixture. */
    static Path tinyApprovedJar(Path directory) throws IOException {
        Path source = directory.resolve("source/owned/ApprovedApi.java");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package owned; public final class ApprovedApi { public static void validate(int value) {} }\n");
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Native journey requires a JDK");
        assertThat(compiler.run(null, null, null, "--release", "21", "-proc:none",
                "-d", classes.toString(), source.toString())).as("Compile tiny owned approved dependency").isZero();
        Path jar = directory.resolve("approved-api.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("owned/ApprovedApi.class"));
            Files.copy(classes.resolve("owned/ApprovedApi.class"), output);
            output.closeEntry();
        }
        return jar;
    }

    static String git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        java.util.concurrent.CompletableFuture<String> output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (java.io.InputStream stream = process.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for local Git fixture command");
            }
            String result = output.get(5, TimeUnit.SECONDS);
            assertThat(process.exitValue()).as("Local Git fixture command failed").isZero();
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
