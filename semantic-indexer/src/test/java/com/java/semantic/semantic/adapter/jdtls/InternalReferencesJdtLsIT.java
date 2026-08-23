package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("jdtls-it")
class InternalReferencesJdtLsIT {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/call-site-resolution");
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("internal-references");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("d".repeat(40));
    private static final String WORKER_SOURCE = "src/main/java/com/example/callsite/Worker.java";

    @TempDir
    Path workingTree;

    @TempDir
    Path workspaceData;

    @Test
    void should_find_repository_local_type_references_with_real_jdt_ls() throws IOException {
        Path home = requireJdtlsHome(System.getenv("JDTLS_HOME"));
        Path root = copyFixture();
        DefaultJdtWorkspaceManager manager = manager(properties(home));
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        SemanticReferenceAnchor anchor = new SemanticReferenceAnchor(
                WORKER_SOURCE, new SemanticPosition(2, 17));

        try {
            List<SemanticReferenceLocation> references = service.findReferences(snapshot, anchor);

            assertThat(references)
                    .allSatisfy(reference -> assertThat(reference)
                            .isInstanceOf(SemanticReferenceLocation.LocalSource.class));
            assertThat(references)
                    .map(SemanticReferenceLocation.LocalSource.class::cast)
                    .extracting(SemanticReferenceLocation.LocalSource::sourceFile)
                    .containsExactlyInAnyOrder(
                            "src/main/java/com/example/callsite/CallSiteScenarios.java",
                            "src/main/java/com/example/callsite/WorkerImpl.java");
        } finally {
            manager.shutdownAll();
        }
    }

    private Path requireJdtlsHome(String configuredHome) {
        assumeTrue(StringUtils.hasText(configuredHome),
                "JDTLS_HOME must be configured for real JDT LS integration tests");
        Path home = Path.of(configuredHome);
        assertThat(Files.isDirectory(home))
                .as("JDTLS_HOME must point at an installed JDT LS directory: %s", home)
                .isTrue();
        return home;
    }

    private JdtLsProperties properties(Path home) {
        return new JdtLsProperties(
                true,
                home,
                workspaceData,
                Duration.ofSeconds(180),
                Duration.ofSeconds(600),
                Duration.ofSeconds(60),
                1,
                Duration.ofMinutes(30),
                Duration.ofMinutes(1),
                "2g");
    }

    private DefaultJdtWorkspaceManager manager(JdtLsProperties properties) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics lifecycleMetrics = new JdtWorkspaceLifecycleMetrics(meterRegistry);
        return new DefaultJdtWorkspaceManager(
                new JdtLsProcessFactory(properties),
                new JdtLsReadinessProbe(properties),
                properties,
                meterRegistry,
                lifecycleMetrics);
    }

    private Path copyFixture() throws IOException {
        Path source = FIXTURE.toAbsolutePath();
        Path target = workingTree.resolve(REPOSITORY_ID.value());
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }
}
