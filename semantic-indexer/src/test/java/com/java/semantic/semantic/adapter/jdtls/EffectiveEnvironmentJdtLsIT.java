package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.analysis.AnalysisTarget;
import com.java.semantic.indexer.analysis.ConservativeAnalysisReuseVerifier;
import com.java.semantic.indexer.analysis.DefaultRepositoryAnalysisPreparation;
import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
@Tag("jdtls-it")
class EffectiveEnvironmentJdtLsIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void attests_reactor_outputs_and_in_repository_artifacts_then_resolves_b_only_module_api() throws Exception {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (Fixture fixture = new Fixture(home, temporaryDirectory)) {
            String firstDependencyDigest;
            String firstProjectEdgeDigest;
            try (PreparedAnalysis first = fixture.prepare("BEFORE")) {
                assertThat(first.plan().sources()).anySatisfy(input ->
                        assertThat(input.sourcePath()).contains("application/src/production/java"));
                firstDependencyDigest = fixture.inRepositoryArtifact(first).contentDigest();
                assertThat(fixture.inRepositoryArtifact(first).byteLength()).isPositive();
                firstProjectEdgeDigest = fixture.projectEdge(first).contentDigest();
                assertThat(fixture.projectEdge(first).byteLength()).isZero();
            }

            fixture.replaceDependencyBytes("B");
            fixture.commitRevisionB();

            try (PreparedAnalysis second = fixture.prepare("AFTER")) {
                assertThat(fixture.inRepositoryArtifact(second).contentDigest()).isNotEqualTo(firstDependencyDigest);
                assertThat(fixture.projectEdge(second).contentDigest()).isNotEqualTo(firstProjectEdgeDigest);
                assertThat(second.snapshot().revision().value()).isEqualTo(fixture.revisionB());
                assertThat(second.fingerprint().inputs().projects()).allSatisfy(project ->
                        assertThat(project.classpath()).allSatisfy(artifact ->
                                assertThat(artifact.logicalId()).doesNotStartWith("/").doesNotStartWith("file:")));
                assertThat(Fixture.artifacts(second)).extracting(AnalysisInputs.Artifact::logicalId)
                        .anySatisfy(id -> assertThat(id).contains("junit-jupiter-api"))
                        .anySatisfy(id -> assertThat(id).contains("assertj-core"))
                        .anySatisfy(id -> assertThat(id).contains("slf4j-api"))
                        .anySatisfy(id -> assertThat(id).contains("commons-lang3"));
                assertThat(fixture.openedDependencyHover(second,
                        "application/src/production/java/example/app/DependencyUse.java", "LoggerFactory"))
                        .contains("org.slf4j.LoggerFactory");
                assertThat(fixture.openedDependencyHover(second,
                        "application/src/production/java/example/app/DependencyUse.java", "Assertions"))
                        .contains("org.junit.jupiter.api.Assertions");
                assertThat(fixture.openedDependencyHover(second,
                        "application/src/production/java/example/app/UseApi.java", "Api"))
                        .contains("example.api.Api");
                SemanticCallResolution resolution = fixture.resolveBOnlyDependency(second);
                AnalysisInputs.Root testRoot = second.fingerprint().inputs().projects().stream()
                        .flatMap(project -> project.roots().stream())
                        .filter(root -> root.path().contains("src/test/java")).findFirst().orElseThrow();
                assertThat(testRoot.included()).isFalse();
                assertThat(testRoot.exclusions()).contains("test");
                assertThat(resolution.call()).isPresent().get().satisfies(call -> {
                    assertThat(call.target()).isPresent().get().satisfies(target -> {
                        assertThat(target.packageName()).isEqualTo("example.api");
                        assertThat(target.className()).isEqualTo("Api");
                        assertThat(target.methodName()).isEqualTo("bOnly");
                        assertThat(target.parameterTypes()).containsExactly("int");
                        assertThat(target.returnType()).isEqualTo("int");
                    });
                    assertThat(call.rawSignature()).contains("bOnly").contains("int");
                });
            }
        }
    }

    @Test
    void proves_a_deep_package_private_declaration_in_a_modular_imported_root() throws Exception {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (Fixture fixture = new Fixture(home, temporaryDirectory)) {
            fixture.addModularDeepRoot();

            try (PreparedAnalysis analysis = fixture.prepare("BEFORE")) {
                assertThat(analysis.readinessEvidence().projects())
                        .anySatisfy(project -> {
                            assertThat(project.projectPath()).isEqualTo("modular");
                            assertThat(project.imported()).isTrue();
                            assertThat(project.verifiedSourcePaths()).contains("modular/src/main/java");
                        });
            }
        }
    }

    @Test
    void ignores_unrelated_host_files_but_rejects_changed_analysis_dependencies() throws Exception {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        JdtLsProperties properties = new JdtLsProperties(
                true, home, temporaryDirectory.resolve("workspaces"),
                Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(30),
                1, Duration.ofMinutes(1), Duration.ofMinutes(1), "1g");
        Path unrelated = Files.createTempFile(Path.of("").toAbsolutePath(), "analysis-host-state-", ".tmp");
        try {
            Files.writeString(unrelated, "before");
            try (Fixture fixture = new Fixture(temporaryDirectory, properties);
                    PreparedAnalysis analysis = fixture.prepare("BEFORE")) {
                Files.writeString(unrelated, "after");
                assertThatCode(analysis::verifyUnchangedInputs).doesNotThrowAnyException();

                fixture.replaceDependencyBytes("changed");
                assertThatThrownBy(analysis::verifyUnchangedInputs)
                        .isInstanceOf(IllegalStateException.class);
            }
        } finally {
            Files.deleteIfExists(unrelated);
        }
    }

    @Test
    void rejects_reuse_of_generations_from_the_unversioned_analyzer() throws Exception {
        Path home = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (Fixture fixture = new Fixture(home, temporaryDirectory);
                PreparedAnalysis analysis = fixture.prepare("BEFORE")) {
            AnalysisInputs inputs = analysis.fingerprint().inputs();
            AnalysisInputs historicalInputs = new AnalysisInputs(inputs.contractVersion(),
                    "771cead1eea149e0a853154955e187d9f07c2468b1da11c9f011f5ea740026d9",
                    inputs.jdtLsDigest(), inputs.launcherJdkDigest(), inputs.importInputsDigest(), inputs.projects());
            AnalysisFingerprint historicalFingerprint = AnalysisFingerprint.from(historicalInputs);
            SemanticAnalysisEvidence evidence = analysis.readinessEvidence();
            SemanticAnalysisEvidence historicalEvidence = new SemanticAnalysisEvidence(evidence.contractVersion(),
                    historicalFingerprint.digest(), evidence.buildStatus(), evidence.projects(),
                    evidence.resolution(), evidence.limitations());
            SelectedGeneration selected = new SelectedGeneration(analysis.snapshot().repositoryId(),
                    analysis.snapshot().revision(), new GenerationId("previous-generation"),
                    new ManifestDigest("a".repeat(64)));
            SealedGeneration current = new SealedGeneration(selected, analysis.fingerprint(), evidence);
            SealedGeneration historical = new SealedGeneration(selected, historicalFingerprint, historicalEvidence);
            ConservativeAnalysisReuseVerifier verifier = new ConservativeAnalysisReuseVerifier(
                    (candidate, target) -> candidate.fingerprint().equals(AnalysisFingerprint.from(inputs))
                            && candidate.equals(current));
            AnalysisTarget target = new AnalysisTarget(analysis.snapshot(), "analyzer-reuse", "BEFORE");

            assertThat(verifier.matches(current, target)).isTrue();
            assertThat(verifier.matches(historical, target))
                    .as("same SHA and dependencies cannot authorize an obsolete unversioned analyzer")
                    .isFalse();
        }
    }


    private static final class Fixture implements AutoCloseable {
        private final Path repository;
        private final Path compilationScratch;
        private final Git git;
        private final DefaultJdtWorkspaceManager manager;
        private final DefaultRepositoryAnalysisPreparation preparation;
        private String revisionB;

        private Fixture(Path home, Path temporaryDirectory) throws Exception {
            this(temporaryDirectory, new JdtLsProperties(
                    true, home, temporaryDirectory.resolve("workspaces"),
                    Path.of(System.getProperty("java.home"), "bin", "java"),
                    JdtLsProperties.IsolationMode.LOCAL_TRUSTED, 0, 0,
                    temporaryDirectory, Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(30),
                    2, Duration.ofMinutes(1), Duration.ofMinutes(1), "1g"));
        }

        private Fixture(Path temporaryDirectory, JdtLsProperties properties) throws Exception {
            repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
            compilationScratch = Files.createDirectories(temporaryDirectory.resolve("compilation-scratch"));
            writeRepository();
            writeDependency("A");
            git = Git.init().setDirectory(repository.toFile()).call();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("A").setAuthor("test", "test@example.invalid").call();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            manager = new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties),
                    new JdtLsReadinessProbe(properties), properties, registry,
                    new JdtWorkspaceLifecycleMetrics(registry));
            preparation = new DefaultRepositoryAnalysisPreparation(manager,
                    new JdtLsEffectiveEnvironmentInspector(properties), new FullIndexPlanner());
        }

        private void addModularDeepRoot() throws Exception {
            Path parent = repository.resolve("pom.xml");
            Files.writeString(parent, Files.readString(parent).replace(
                    "<module>application</module>", "<module>application</module><module>modular</module>"));
            Path sourceRoot = Files.createDirectories(repository.resolve("modular/src/main/java"));
            Files.writeString(repository.resolve("modular/pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>modular</artifactId>
                    </project>
                    """);
            Files.writeString(sourceRoot.resolve("module-info.java"), "module example.modular { }\n");
            Path deepPackage = Files.createDirectories(sourceRoot.resolve("org/a/b/c/d/e/f/g/h/i/j/k/l/m"));
            Files.writeString(deepPackage.resolve("OddFilename.java"),
                    "package org.a.b.c.d.e.f.g.h.i.j.k.l.m; class DeepDeclaration { }\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add modular deep root").setAuthor("test", "test@example.invalid").call();
        }

        private PreparedAnalysis prepare(String stage) {
            return preparation.prepare(new AnalysisTarget(snapshot(), "effective-inputs", stage));
        }

        private void replaceDependencyBytes(String value) throws Exception {
            writeDependency(value);
        }

        private void commitRevisionB() throws Exception {
            Files.writeString(repository.resolve("api/src/main/java/example/api/Api.java"),
                    "package example.api; public final class Api { public static int bOnly(int value) { return value + 1; } }\n");
            Files.writeString(repository.resolve("application/src/production/java/example/app/UseApi.java"),
                    "package example.app; import example.api.Api; public class UseApi { public int b() { return Api.bOnly(7); } }\n");
            git.add().addFilepattern(".").call();
            revisionB = git.commit().setMessage("B").setAuthor("test", "test@example.invalid").call().getName();
        }

        private String revisionB() {
            return revisionB;
        }

        private AnalysisInputs.Artifact projectEdge(PreparedAnalysis analysis) {
            return artifacts(analysis).stream().filter(artifact -> artifact.kind().equals("PROJECT_EDGE"))
                    .filter(artifact -> artifact.logicalId().contains("api/target/classes"))
                    .findFirst().orElseThrow();
        }

        private AnalysisInputs.Artifact inRepositoryArtifact(PreparedAnalysis analysis) {
            return artifacts(analysis).stream().filter(artifact -> artifact.kind().equals("CLASSPATH"))
                    .filter(artifact -> artifact.logicalId().startsWith("library:sha256:"))
                    .findFirst().orElseThrow();
        }

        private static List<AnalysisInputs.Artifact> artifacts(PreparedAnalysis analysis) {
            return analysis.fingerprint().inputs().projects().stream()
                    .flatMap(project -> Stream.concat(project.classpath().stream(), project.modulepath().stream()))
                    .toList();
        }

        private SemanticCallResolution resolveBOnlyDependency(PreparedAnalysis analysis) throws IOException {
            Path source = repository.resolve("application/src/production/java/example/app/UseApi.java");
            String content = Files.readString(source);
            int methodOffset = content.indexOf(" b()");
            int invocationOffset = content.indexOf("bOnly");
            SemanticRange methodRange = range(0, methodOffset + 1, content.length());
            SemanticRange selectionRange = range(0, methodOffset + 1, methodOffset + 2);
            SemanticMethod caller = new SemanticMethod("example.app", "UseApi", "b", List.of(), "int",
                    new SemanticLocation(source.toUri().toString(), methodRange, selectionRange));
            SemanticRange invocationRange = range(0, invocationOffset, invocationOffset + "bOnly".length());
            return analysis.semanticService().resolveCallResolutionAt(analysis.snapshot(), caller,
                    new SemanticCallSite(invocationRange, new SemanticPosition(0, invocationOffset)));
        }

        private static SemanticPosition positionAt(String source, int offset) {
            int line = 0;
            int lineStart = 0;
            for (int index = 0; index < offset; index++) {
                if (source.charAt(index) == '\n') {
                    line++;
                    lineStart = index + 1;
                }
            }
            return new SemanticPosition(line, offset - lineStart);
        }
        private String openedDependencyHover(PreparedAnalysis analysis, String relativeSource, String typeName)
                throws Exception {
            Path source = repository.resolve(relativeSource);
            String text = Files.readString(source);
            String uri = source.toUri().toString();
            int offset = text.indexOf(typeName) + 1;
            SemanticPosition position = positionAt(text, offset);
            Field field = Lsp4jJavaSemanticService.class.getDeclaredField("boundSession");
            field.setAccessible(true);
            JdtWorkspaceSession session = (JdtWorkspaceSession) field.get(analysis.semanticService());
            return session.withDocumentUri(uri, () -> {
                session.call("test:didOpen", server -> {
                    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                            new TextDocumentItem(uri, "java", 1, text)));
                    return CompletableFuture.completedFuture(null);
                });
                try {
                    Hover result = session.call("test:hover", server -> server.getTextDocumentService().hover(
                            new HoverParams(new TextDocumentIdentifier(uri),
                                    new Position(position.line(), position.character()))));
                    if (Objects.isNull(result)) {
                        throw new IllegalStateException("no hover for " + typeName);
                    }
                    return result.toString();
                } finally {
                    session.call("test:didClose", server -> {
                        server.getTextDocumentService().didClose(
                                new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
                        return CompletableFuture.completedFuture(null);
                    });
                }
            });
        }

        private static SemanticRange range(int line, int start, int end) {
            return new SemanticRange(new SemanticPosition(line, start), new SemanticPosition(line, end));
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
                    <properties><maven.compiler.release>17</maven.compiler.release></properties>
                    <modules><module>api</module><module>application</module></modules></project>
                    """);
            Path api = Files.createDirectories(repository.resolve("api/src/main/java/example/api"));
            Files.writeString(repository.resolve("api/pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>api</artifactId>
                    </project>
                    """);
            Files.writeString(api.resolve("Api.java"),
                    "package example.api; public final class Api { public static String aOnly() { return \"A\"; } }\n");
            Path application = Files.createDirectories(repository.resolve("application/src/production/java/example/app"));
            Files.createDirectories(repository.resolve("libraries"));
            Files.writeString(repository.resolve("application/pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>application</artifactId>
                    <build><sourceDirectory>src/production/java</sourceDirectory></build><dependencies>
                    <dependency><groupId>example</groupId><artifactId>api</artifactId><version>1</version></dependency>
                    <dependency><groupId>example</groupId><artifactId>fixture</artifactId><version>1</version><scope>system</scope>
                    <systemPath>${project.basedir}/../libraries/fixture.jar</systemPath></dependency>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.17</version></dependency>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.18.0</version><scope>runtime</scope></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId><version>5.14.2</version><scope>provided</scope></dependency>
                    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>3.27.7</version><scope>test</scope></dependency>
                    </dependencies></project>
                    """);
            Files.writeString(application.resolve("UseApi.java"),
                    "package example.app; import example.api.Api; public class UseApi { public String a() { return Api.aOnly(); } }\n");
            Files.writeString(application.resolve("DependencyUse.java"), """
                    package example.app;
                    import org.junit.jupiter.api.Assertions;
                    import org.slf4j.LoggerFactory;
                    public class DependencyUse {
                        void runtime() { LoggerFactory.getLogger("dependency"); }
                        void provided() { Assertions.assertTrue(true); }
                    }
                    """);
            Path testSource = Files.createDirectories(repository.resolve("application/src/test/java/example/app"));
            Files.writeString(testSource.resolve("DependencyUseTest.java"), """
                    package example.app;
                    import static org.assertj.core.api.Assertions.assertThat;
                    class DependencyUseTest { void testOnly() { assertThat(true).isTrue(); } }
                    """);
        }

        private void writeDependency(String value) throws Exception {
            Path source = Files.createTempDirectory(compilationScratch, "source-");
            Path packageRoot = Files.createDirectories(source.resolve("example/fixture"));
            Path api = packageRoot.resolve("Artifact.java");
            Files.writeString(api, "package example.fixture; public final class Artifact { public static String value() { return \""
                    + value + "\"; } }\n");
            Path classes = Files.createDirectories(source.resolve("classes"));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).isNotNull();
            assertThat(compiler.run(null, null, null, "-d", classes.toString(), api.toString())).isZero();
            try (OutputStream output = Files.newOutputStream(repository.resolve("libraries/fixture.jar"));
                    JarOutputStream jar = new JarOutputStream(output)) {
                JarEntry entry = new JarEntry("example/fixture/Artifact.class");
                entry.setTime(0);
                jar.putNextEntry(entry);
                jar.write(Files.readAllBytes(classes.resolve("example/fixture/Artifact.class")));
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
