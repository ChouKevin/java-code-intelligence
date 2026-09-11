package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.incremental.ChangeKind;
import com.java.semantic.indexer.incremental.ChangedSource;
import com.java.semantic.indexer.incremental.IncrementalIndexPlan;
import com.java.semantic.indexer.incremental.IncrementalIndexPlanner;
import com.java.semantic.indexer.incremental.ModuleLocator;
import com.java.semantic.indexer.incremental.SourceContractChangeDetector;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.adapter.jdtls.DefaultJdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtLsHomeRequirement;
import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceLifecycleMetrics;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses a real JDT LS process while checking the source selections published by incremental planning. */
@Tag("jdtls-it")
class IncrementalPublicationJdtLsIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exports_only_reanalyzed_body_and_signature_sources_and_omits_deleted_sources_after_real_jdt_ls_analysis() throws IOException {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        Path root = Files.createDirectories(temporaryDirectory.resolve("repository"));
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></project>");
        Path source = Files.createDirectories(root.resolve("src/main/java/demo")).resolve("Sample.java");
        Files.writeString(source, "package demo; public class Sample { int value() { return 1; } }");
        RepositoryId repositoryId = RepositoryId.of("jdt-incremental");
        DefaultJdtWorkspaceManager manager = manager(jdtLsHome);
        try {
            RepositoryRevision firstRevision = new RepositoryRevision("a".repeat(40));
            manager.getOrStart(new RepositorySnapshot(repositoryId, root, firstRevision));
            JdtLsRepositoryIndexExporter exporter = new JdtLsRepositoryIndexExporter(new Lsp4jJavaSemanticService(manager));
            FullIndexPlan firstPlan = new FullIndexPlanner().plan(root);
            assertThat(exporter.export(repositoryId, firstRevision, new GenerationId("g1"), firstPlan)).isNotEmpty();

            Files.writeString(source, "package demo; public class Sample { int value() { return 2; } }");
            RepositoryRevision bodyRevision = new RepositoryRevision("b".repeat(40));
            manager.getOrStart(new RepositorySnapshot(repositoryId, root, bodyRevision));
            FullIndexPlan bodyPlan = new FullIndexPlanner().plan(root);
            assertThat(exporter.export(repositoryId, bodyRevision, new GenerationId("g2"), bodyPlan))
                    .flatMap(SourceIndexBatch::symbols).extracting(document -> document.name()).contains("value");

            Files.writeString(source, "package demo; public class Sample { String value() { return \"two\"; } }");
            RepositoryRevision signatureRevision = new RepositoryRevision("c".repeat(40));
            manager.getOrStart(new RepositorySnapshot(repositoryId, root, signatureRevision));
            FullIndexPlan signaturePlan = new FullIndexPlanner().plan(root);
            assertThat(exporter.export(repositoryId, signatureRevision, new GenerationId("g3"), signaturePlan))
                    .flatMap(SourceIndexBatch::symbols).extracting(document -> document.signature()).contains("demo.Sample#value()");

            IncrementalIndexPlan deletePlan = planner(ChangedSource.delete("src/main/java/demo/Sample.java", "class Sample {}"), noModules())
                    .plan(IncrementalIndexPlanner.PublishedIndex.fromPublishedFacts(List.of("src/main/java/demo/Sample.java"), List.of(), List.of(),
                            List.of(), List.of()), firstRevision.value(), signatureRevision.value(), List.of());
            assertThat(deletePlan.deletedPaths()).containsExactly("src/main/java/demo/Sample.java");
            assertThat(deletePlan.reanalyzePaths()).isEmpty();

            IncrementalIndexPlan pomPlan = planner(ChangedSource.modify("pom.xml", "<project/>", "<project><dependencies/></project>"), modules())
                    .plan(IncrementalIndexPlanner.PublishedIndex.fromPublishedFacts(List.of("src/main/java/demo/Sample.java"), List.of(), List.of(),
                            List.of(), List.of()), firstRevision.value(), signatureRevision.value(), List.of("src/main/java/demo/Sample.java"));
            assertThat(pomPlan.reanalyzePaths()).containsExactly("src/main/java/demo/Sample.java");
            assertThat(pomPlan.copyPaths()).isEmpty();
        } finally {
            manager.shutdownAll();
        }
    }

    private static IncrementalIndexPlanner planner(ChangedSource changedSource, ModuleLocator moduleLocator) {
        return new IncrementalIndexPlanner((parent, selected) -> List.of(changedSource),
                (change, declarations) -> SourceContractChangeDetector.Impact.bodyOrPrivateChange(), moduleLocator);
    }

    private static ModuleLocator noModules() {
        return new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) { return Optional.empty(); }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) { return Optional.empty(); }

            @Override
            public Optional<Set<String>> supportedSources(String module) { return Optional.empty(); }
        };
    }

    private static ModuleLocator modules() {
        return new ModuleLocator() {
            @Override
            public Optional<String> locate(String path) { return Optional.of("demo"); }

            @Override
            public Optional<Set<String>> reverseDependencyClosure(String module) { return Optional.of(Set.of("demo")); }

            @Override
            public Optional<Set<String>> supportedSources(String module) { return Optional.of(Set.of("src/main/java/demo/Sample.java")); }
        };
    }

    private DefaultJdtWorkspaceManager manager(Path home) {
        JdtLsProperties properties = new JdtLsProperties(true, home, temporaryDirectory.resolve("workspace"), Duration.ofSeconds(180),
                Duration.ofSeconds(600), Duration.ofSeconds(60), 1, Duration.ofMinutes(30), Duration.ofMinutes(1), "2g");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties), new JdtLsReadinessProbe(properties), properties, registry,
                System::nanoTime, new JdtWorkspaceLifecycleMetrics(registry));
    }
}
