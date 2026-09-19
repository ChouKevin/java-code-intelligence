package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.analysis.AnalysisTarget;
import com.java.semantic.indexer.analysis.DefaultRepositoryAnalysisPreparation;
import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("jdtls-it")
class EffectiveEnvironmentJdtLsIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void should_attest_changed_external_dependency_bytes_in_a_fresh_b_lease() throws Exception {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (Fixture fixture = new Fixture(home, temporaryDirectory)) {
            String firstDigest;
            try (PreparedAnalysis first = fixture.prepare("A")) {
                firstDigest = fixture.observedDependencyDigest(first);
                assertThat(first.plan().sources()).anySatisfy(input ->
                        assertThat(input.sourcePath()).contains("src/production/java"));
            }

            fixture.replaceDependencyBytes("B");
            fixture.commitRevisionB();

            try (PreparedAnalysis second = fixture.prepare("B")) {
                assertThat(fixture.observedDependencyDigest(second)).isNotEqualTo(firstDigest);
                assertThat(second.snapshot().revision().value()).isEqualTo(fixture.revisionB());
                assertThat(second.fingerprint().inputs().projects()).allSatisfy(project ->
                        assertThat(project.classpath()).allSatisfy(artifact ->
                                assertThat(artifact.logicalId()).doesNotStartWith("/").doesNotStartWith("file:")));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path repository;
        private final Path dependencies;
        private final Git git;
        private final DefaultJdtWorkspaceManager manager;
        private final DefaultRepositoryAnalysisPreparation preparation;
        private String revisionB;

        private Fixture(Path home, Path temporaryDirectory) throws Exception {
            repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
            dependencies = Files.createDirectories(temporaryDirectory.resolve("dependencies"));
            writeRepository();
            writeDependency("A");
            git = Git.init().setDirectory(repository.toFile()).call();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("A").setAuthor("test", "test@example.invalid").call();
            JdtLsProperties properties = new JdtLsProperties(
                    true, home, temporaryDirectory.resolve("workspaces"),
                    Path.of(System.getProperty("java.home"), "bin", "java"),
                    JdtLsProperties.IsolationMode.LOCAL_TRUSTED, 0, 0,
                    temporaryDirectory, Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(30),
                    2, Duration.ofMinutes(1), Duration.ofMinutes(1), "1g");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            manager = new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties),
                    new JdtLsReadinessProbe(properties), properties, registry,
                    new JdtWorkspaceLifecycleMetrics(registry));
            preparation = new DefaultRepositoryAnalysisPreparation(manager,
                    new JdtLsEffectiveEnvironmentInspector(properties), new FullIndexPlanner());
        }

        private PreparedAnalysis prepare(String stage) {
            return preparation.prepare(new AnalysisTarget(snapshot(), "effective-inputs", stage));
        }

        private void replaceDependencyBytes(String value) throws Exception {
            writeDependency(value);
        }

        private void commitRevisionB() throws Exception {
            Path source = repository.resolve("application/src/production/java/example/app/UseApi.java");
            Files.writeString(source, "package example.app; import example.dep.Api; public class UseApi { public String b() { return Api.value(); } }\n");
            git.add().addFilepattern(".").call();
            revisionB = git.commit().setMessage("B").setAuthor("test", "test@example.invalid").call().getName();
        }

        private String revisionB() {
            return revisionB;
        }

        private String observedDependencyDigest(PreparedAnalysis analysis) {
            return analysis.fingerprint().inputs().projects().stream()
                    .flatMap(project -> project.classpath().stream())
                    .filter(artifact -> artifact.kind().equals("CLASSPATH"))
                    .findFirst().orElseThrow().contentDigest();
        }

        private RepositorySnapshot snapshot() {
            try {
                return new RepositorySnapshot(RepositoryId.of("effective-inputs"), repository,
                        RepositoryRevision.ofSha(git.getRepository().resolve("HEAD").getName()));
            } catch (IOException exception) {
                throw new IllegalStateException("unable to resolve test revision", exception);
            }
        }

        private void writeRepository() throws IOException {
            Files.writeString(repository.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
                    <modules><module>application</module></modules></project>
                    """);
            Path module = Files.createDirectories(repository.resolve("application/src/production/java/example/app"));
            Files.writeString(repository.resolve("application/pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>application</artifactId>
                    <build><sourceDirectory>src/production/java</sourceDirectory></build><dependencies><dependency>
                    <groupId>example</groupId><artifactId>api</artifactId><version>1</version><scope>system</scope>
                    <systemPath>${project.basedir}/../../dependencies/api.jar</systemPath></dependency></dependencies></project>
                    """);
            Files.writeString(module.resolve("UseApi.java"),
                    "package example.app; import example.dep.Api; public class UseApi { public String a() { return Api.value(); } }\n");
        }

        private void writeDependency(String value) throws Exception {
            Path source = Files.createTempDirectory(dependencies, "source-");
            Path packageRoot = Files.createDirectories(source.resolve("example/dep"));
            Path api = packageRoot.resolve("Api.java");
            Files.writeString(api, "package example.dep; public final class Api { public static String value() { return \"" + value + "\"; } }\n");
            Path classes = Files.createDirectories(source.resolve("classes"));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).isNotNull();
            assertThat(compiler.run(null, null, null, "-d", classes.toString(), api.toString())).isZero();
            try (OutputStream output = Files.newOutputStream(dependencies.resolve("api.jar"));
                    JarOutputStream jar = new JarOutputStream(output)) {
                JarEntry entry = new JarEntry("example/dep/Api.class");
                entry.setTime(0);
                jar.putNextEntry(entry);
                jar.write(Files.readAllBytes(classes.resolve("example/dep/Api.class")));
                jar.closeEntry();
            }
        }

        @Override
        public void close() {
            manager.shutdownAll();
            git.close();
        }
    }
}
