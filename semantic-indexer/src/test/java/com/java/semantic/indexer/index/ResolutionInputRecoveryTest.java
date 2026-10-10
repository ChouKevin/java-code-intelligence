package com.java.semantic.indexer.index;

import com.java.semantic.indexer.application.PreparationCoordinator;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.SourcePreparationService;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real admission/restart/publication and child execution, not mutable settings or copy-only assertions. */
class ResolutionInputRecoveryTest {
    @TempDir Path root;

    @Test
    void original_request_executes_frozen_dependency_after_operator_replacement_and_restart() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            Path jar = jar("d1", "onlyD1");
            RepositoryProperties.RelationsConfig settings = enable(fixture, jar);
            IndexBuildInput input = fixture.prepare("class A { dep.Api api; void use() { api.onlyD1(); } }\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.admit(input, request, Optional.empty());
            String digest = accepted.analysis().analysisPlanDigest().orElseThrow();
            Files.copy(jar("d2", "onlyD2"), jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            settings.getDependencies().getFirst().setSha256(DurableSourceFiles.sha256(jar));
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, fixture.inputs);
            PreparationCoordinator coordinator = coordinator(fixture, reopened);
            assertThatThrownBy(() -> coordinator.prepareSemanticIndex(fixture.git.repository, request,
                    RepositoryRevision.ofSha(input.context().revision())))
                    .isInstanceOfSatisfying(PreparationRequestReusedException.class,
                            reused -> assertThat(reopened.find(fixture.git.repository, request)).contains(accepted));
            SemanticIndexJob running = reopened.claimNext().orElseThrow();
            IndexBuildExecutor executor = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties);
            IndexManifest manifest = executor.build(input, running, fixture.stage(input), () -> { });
            Receipt receipt = fixture.indexes.publish(running, fixture.stage(input), manifest);
            reopened.complete(fixture.git.repository, new IndexJobId(running.jobId()), receipt);
            assertThat(manifest.analysisPlanDigest()).contains(digest);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.generation(input).resolve("index.sqlite"));
                    Statement statement = connection.createStatement();
                    ResultSet targets = statement.executeQuery("SELECT target_json FROM external_targets")) {
                StringBuilder signatures = new StringBuilder();
                while (targets.next()) signatures.append(targets.getString(1));
                assertThat(signatures.toString()).contains("onlyD1").doesNotContain("onlyD2");
            }
            fixture.inputs.cleanupTerminal(reopened.find(fixture.git.repository, request).orElseThrow());
            assertThat(Files.exists(fixture.inputs.directory(accepted))).isFalse();
            assertThat(fixture.indexes.receipt(running)).contains(receipt);
            // Reconcile a lost terminal write after owned inputs have been cleaned.
            // The current deployment no longer even has the formerly accepted JDK.
            Files.write(fixture.git.admin.resolve("index/jobs/orders").resolve(request.value() + ".json"),
                    fixture.mapper.writeValueAsBytes(running));
            ResolutionInputStore changedEnvironment = new ResolutionInputStore(fixture.properties, fixture.mapper,
                    new PlatformIdentity(root.resolve("retired-jdk")), fixture.registry);
            FileSemanticIndexJobStore recovered = reopen(fixture);
            recovered.recover(fixture.indexes, changedEnvironment);
            assertThat(recovered.find(fixture.git.repository, request).orElseThrow().publication()).contains(receipt);
            assertThat(recovered.claimNext()).isEmpty();
        }
    }

    @Test
    void corrupt_accepted_snapshot_fails_original_job_instead_of_binding_new_configuration() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            enable(fixture, jar("d1", "onlyD1"));
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.admit(input, request, Optional.empty());
            Files.writeString(fixture.inputs.directory(accepted).resolve("dependency-000.jar"), "corrupt");
            fixture.properties.getRepositories().get("orders").setRelations(new RepositoryProperties.RelationsConfig());
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, fixture.inputs);
            SemanticIndexJob failed = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(failed.jobId()).isEqualTo(accepted.jobId());
            assertThat(failed.failureCode()).contains("INDEX_UNAVAILABLE");
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
        }
    }

    @Test
    void legacy_accepted_work_remains_syntax_only_when_relations_are_now_enabled() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.admit(input, request, Optional.empty());
            tools.jackson.databind.node.ObjectNode legacy = fixture.mapper.valueToTree(accepted);
            legacy.put("formatVersion", 1);
            legacy.remove("acceptedAnalysis");
            Files.write(fixture.git.admin.resolve("index/jobs/orders").resolve(request.value() + ".json"), fixture.mapper.writeValueAsBytes(legacy));
            fixture.inputs.cleanupTerminal(accepted.failed("WORKER_INTERRUPTED"));
            fixture.properties.getRepositories().get("orders").getRelations().setEnabled(true);
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, fixture.inputs);
            SemanticIndexJob running = reopened.claimNext().orElseThrow();
            assertThat(running.analysis()).isEqualTo(IndexAnalysisIntent.syntax());
            IndexBuildExecutor executor = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties);
            IndexManifest manifest = executor.build(input, running, fixture.stage(input), () -> { });
            assertThat(manifest.profile()).isEqualTo(com.java.semantic.model.index.IndexContract.syntaxProfile());
            assertThat(manifest.analysisPlanDigest()).isEmpty();
        }
    }

    @Test
    void overlapping_roots_and_symbolic_dependency_never_become_accepted_work() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            Path jar = jar("d1", "onlyD1");
            RepositoryProperties.RelationsConfig settings = enable(fixture, jar);
            IndexBuildInput input = fixture.prepare("class A {}\n");
            settings.setSourceRoots(List.of("", "nested"));
            PreparationRequestId overlapping = IndexArtifactFixture.request();
            assertThatThrownBy(() -> coordinator(fixture, fixture.jobs).prepareSemanticIndex(fixture.git.repository, overlapping,
                    RepositoryRevision.ofSha(input.context().revision()))).isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.jobs.find(fixture.git.repository, overlapping)).isEmpty();
            settings.setSourceRoots(List.of(""));
            Path link = root.resolve("dependency-link.jar");
            Files.createSymbolicLink(link, jar);
            settings.getDependencies().getFirst().setPath(link.toString());
            PreparationRequestId symbolic = IndexArtifactFixture.request();
            assertThatThrownBy(() -> coordinator(fixture, fixture.jobs).prepareSemanticIndex(fixture.git.repository, symbolic,
                    RepositoryRevision.ofSha(input.context().revision()))).isInstanceOf(com.java.semantic.model.index.IndexFailure.class);
            assertThat(fixture.jobs.find(fixture.git.repository, symbolic)).isEmpty();
            assertThat(fixture.jobs.claimNext()).isEmpty();
        }
    }

    @Test
    void missing_accepted_plan_fails_original_job_and_does_not_acquire_current_settings() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            enable(fixture, jar("d1", "onlyD1"));
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.admit(input, request, Optional.empty());
            Files.delete(fixture.inputs.directory(accepted).resolve("plan.json"));
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, fixture.inputs);
            assertThat(reopened.find(fixture.git.repository, request).orElseThrow().failureCode()).contains("INDEX_UNAVAILABLE");
            assertThat(reopened.claimNext()).isEmpty();
        }
    }

    @Test
    void busy_physical_worker_blocks_cleanup_and_new_repository_work_after_terminal_write() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexJob accepted = fixture.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            try (ResolutionInputStore.WorkerLease lease = ResolutionInputStore.acquireWorker(fixture.inputs.directory(running), fixture.inputs.descriptor(running))) {
                SemanticIndexJob failed = fixture.jobs.fail(fixture.git.repository, new IndexJobId(running.jobId()), "WORKER_INTERRUPTED");
                fixture.inputs.cleanupTerminal(failed);
                assertThat(Files.exists(fixture.inputs.directory(accepted).resolve("descriptor.json"))).isTrue();
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isTrue();
                assertThatThrownBy(() -> coordinator(fixture, fixture.jobs).prepareSource(fixture.git.repository,
                        IndexArtifactFixture.request(), Optional.empty())).isInstanceOf(IndexJobAlreadyActiveException.class);
                assertThatThrownBy(() -> fixture.inputs.recover(fixture.jobs)).isInstanceOf(IllegalStateException.class);
            }
            fixture.inputs.cleanupTerminal(fixture.jobs.find(fixture.git.repository, new IndexJobId(running.jobId())).orElseThrow());
            assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
            assertThat(Files.exists(fixture.inputs.directory(accepted))).isFalse();
        }
    }

    @Test
    void startup_removes_proven_unaccepted_orphan_but_never_adopts_it_or_touches_unknown_owner() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            enable(fixture, jar("d1", "onlyD1"));
            IndexBuildInput input = fixture.prepare("class A {}\n");
            IndexJobId orphan = IndexJobId.create();
            PreparationRequestId request = IndexArtifactFixture.request();
            fixture.inputs.freeze(input, orphan, request, fixture.properties.getRepositories().get("orders").getRelations(), () -> { });
            Path leaf = fixture.git.admin.resolve("index/inputs").resolve(orphan.value());
            // Crash after ownership, before precreating the lease; no job and no child may exist yet.
            Files.delete(leaf.resolve("worker.lock"));
            Path unknown = fixture.git.admin.resolve("index/inputs/operator-owned");
            Files.createDirectory(unknown);
            Files.writeString(unknown.resolve("descriptor.json"), "not service ownership");
            fixture.inputs.recover(fixture.jobs);
            assertThat(Files.exists(leaf)).isFalse();
            assertThat(Files.readString(unknown.resolve("descriptor.json"))).isEqualTo("not service ownership");
            assertThat(fixture.jobs.find(fixture.git.repository, request)).isEmpty();
            assertThat(fixture.jobs.claimNext()).isEmpty();
        }
    }

    @Test
    void restart_finishes_owned_cleanup_after_descriptor_removal_and_preserves_unknown_neighbor() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexJob accepted = fixture.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            fixture.jobs.fail(fixture.git.repository, new IndexJobId(running.jobId()), "WORKER_INTERRUPTED");
            Path leaf = fixture.inputs.directory(accepted);
            ResolutionInputStore.OwnedInput owner = fixture.inputs.descriptor(accepted);
            Path intents = fixture.git.admin.resolve("index/input-cleanup");
            DurableSourceFiles.ensureDirectories(fixture.git.admin, intents, DurableSourceFiles.Visibility.PRIVATE);
            Path intent = intents.resolve(owner.jobId() + ".json");
            DurableSourceFiles.atomicBytes(intent, fixture.mapper.writeValueAsBytes(owner),
                    IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            // Durable on-disk crash checkpoint: cleanup has removed the descriptor,
            // but not yet its lock/leaf or the external deletion authority.
            Files.delete(leaf.resolve("descriptor.json"));
            DurableSourceFiles.forceDirectory(leaf);
            Path unknown = fixture.git.admin.resolve("index/inputs/operator-owned");
            Files.createDirectory(unknown);
            Files.writeString(unknown.resolve("worker.lock"), "unowned neighbor");

            ResolutionInputStore restarted = new ResolutionInputStore(fixture.properties, fixture.mapper,
                    new PlatformIdentity(), fixture.registry);
            FileSemanticIndexJobStore jobs = reopen(fixture);
            jobs.recover(fixture.indexes, restarted);
            restarted.recover(jobs);

            assertThat(Files.exists(leaf)).as("external authority survives descriptor deletion").isFalse();
            assertThat(Files.exists(intent)).as("completed deletion consumes its authority").isFalse();
            assertThat(Files.readString(unknown.resolve("worker.lock"))).isEqualTo("unowned neighbor");
            assertThat(jobs.find(fixture.git.repository, new IndexJobId(running.jobId())).orElseThrow().failureCode())
                    .contains("WORKER_INTERRUPTED");
        }
    }

    @Test
    void invalid_input_fails_before_acceptance_and_owned_partial_snapshot_is_cleaned() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            Path jar = jar("d1", "onlyD1");
            RepositoryProperties.RelationsConfig settings = enable(fixture, jar);
            settings.getDependencies().getFirst().setSha256("0".repeat(64));
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            assertThatThrownBy(() -> coordinator(fixture, fixture.jobs).prepareSemanticIndex(fixture.git.repository, request,
                    RepositoryRevision.ofSha(input.context().revision()))).isInstanceOf(com.java.semantic.model.index.IndexFailure.class);
            assertThat(fixture.jobs.find(fixture.git.repository, request)).isEmpty();
            assertThat(fixture.jobs.claimNext()).isEmpty();
            try (java.nio.file.DirectoryStream<Path> leaves = Files.newDirectoryStream(fixture.git.admin.resolve("index/inputs"))) {
                assertThat(leaves).isEmpty();
            }
            assertThat(fixture.sources.state(fixture.git.repository).current().orElseThrow().revision()).isEqualTo(input.context().revision());
        }
    }

    @Test
    void changed_platform_fails_accepted_work_without_rebinding_it() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            enable(fixture, jar("d1", "onlyD1"));
            Path home = root.resolve("test-jdk");
            Files.createDirectories(home.resolve("lib"));
            Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.1\"\nIMPLEMENTOR=\"Test deployment\"\n");
            Files.writeString(home.resolve("lib/modules"), "accepted platform content");
            ResolutionInputStore inputs = new ResolutionInputStore(fixture.properties, fixture.mapper, new PlatformIdentity(home), fixture.registry);
            IndexBuildInput input = fixture.prepare("class A {}\n");
            IndexJobId id = IndexJobId.create();
            PreparationRequestId request = IndexArtifactFixture.request();
            IndexAnalysisIntent intent = inputs.freeze(input, id, request, fixture.properties.getRepositories().get("orders").getRelations(), () -> { });
            fixture.jobs.admit(input, id, request, Optional.empty(), intent);
            Files.writeString(home.resolve("lib/modules"), "replacement platform content");
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, inputs);
            assertThat(reopened.find(fixture.git.repository, request).orElseThrow().failureCode()).contains("ENVIRONMENT_CHANGED");
            assertThat(reopened.claimNext()).isEmpty();
        }
    }

    @Test
    void java22_replacement_fails_accepted_job_as_environment_changed_but_not_initial_admission() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            RepositoryProperties.RelationsConfig settings = enable(fixture, jar("d1", "onlyD1"));
            Path home = root.resolve("test-jdk");
            Files.createDirectories(home.resolve("lib"));
            Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.1\"\nIMPLEMENTOR=\"Test deployment\"\n");
            Files.writeString(home.resolve("lib/modules"), "accepted platform content");
            ResolutionInputStore inputs = new ResolutionInputStore(fixture.properties, fixture.mapper, new PlatformIdentity(home), fixture.registry);
            IndexBuildInput input = fixture.prepare("class A {}\n");
            IndexJobId id = IndexJobId.create();
            PreparationRequestId request = IndexArtifactFixture.request();
            IndexAnalysisIntent intent = inputs.freeze(input, id, request, settings, () -> { });
            SemanticIndexJob accepted = fixture.jobs.admit(input, id, request, Optional.empty(), intent);
            Files.writeString(home.resolve("release"), "JAVA_VERSION=\"22.0.1\"\nIMPLEMENTOR=\"Test deployment\"\n");
            FileSemanticIndexJobStore reopened = reopen(fixture);
            reopened.recover(fixture.indexes, inputs);
            SemanticIndexJob failed = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(failed.jobId()).isEqualTo(accepted.jobId());
            assertThat(failed.failureCode()).contains("ENVIRONMENT_CHANGED");
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
            PreparationRequestId initial = IndexArtifactFixture.request();
            assertThatThrownBy(() -> inputs.freeze(input, IndexJobId.create(), initial, settings, () -> { }))
                    .isInstanceOfSatisfying(com.java.semantic.model.index.IndexFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo(com.java.semantic.model.index.IndexFailure.Code.INDEX_UNAVAILABLE));
            assertThat(reopened.find(fixture.git.repository, initial)).isEmpty();
        }
    }
    @Test
    void corrupted_frozen_rebuild_preserves_previous_ready_generation() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            enable(fixture, jar("d1", "onlyD1"));
            IndexBuildInput original = fixture.prepare("class A { dep.Api api; void use() { api.onlyD1(); } }\n");
            fixture.admit(original, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexBuildExecutor executor = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties);
            IndexManifest manifest = executor.build(original, running, fixture.stage(original), () -> { });
            Receipt ready = fixture.indexes.publish(running, fixture.stage(original), manifest);
            fixture.inputs.cleanupTerminal(fixture.jobs.complete(fixture.git.repository, new IndexJobId(running.jobId()), ready));
            IndexBuildInput rebuild = fixture.input(original.context());
            SemanticIndexJob accepted = fixture.admit(rebuild, IndexArtifactFixture.request(), Optional.of(original.generation()));
            Files.writeString(fixture.inputs.directory(accepted).resolve("dependency-000.jar"), "corrupt");
            SemanticIndexManager manager = new SemanticIndexManager(fixture.jobs,
                    new IndexSourceAccess(fixture.sources, fixture.registry, fixture.properties), executor,
                    fixture.indexes, fixture.properties, fixture.inputs);
            SemanticIndexJob failed = manager.execute(fixture.jobs.claimNext().orElseThrow());
            assertThat(failed.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(failed.failureCode()).contains("INDEX_UNAVAILABLE");
            assertThat(fixture.indexes.selected(original.context())).contains(ready);
            assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
            assertThat(Files.exists(fixture.stage(rebuild))).isFalse();
        }
    }


    private static FileSemanticIndexJobStore reopen(IndexArtifactFixture fixture) {
        return new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
    }
    private static PreparationCoordinator coordinator(IndexArtifactFixture fixture, FileSemanticIndexJobStore jobs) {
        return new PreparationCoordinator(fixture.sourceJobs, jobs, new SourcePreparationService(fixture.sourceJobs, fixture.sources, fixture.registry),
                new IndexSourceAccess(fixture.sources, fixture.registry, fixture.properties), fixture.indexes, fixture.registry, fixture.inputs, fixture.properties);
    }
    private static RepositoryProperties.RelationsConfig enable(IndexArtifactFixture fixture, Path jar) throws Exception {
        RepositoryProperties.RelationsConfig settings = fixture.properties.getRepositories().get("orders").getRelations();
        settings.setEnabled(true);
        settings.setSourceRoots(List.of(""));
        RepositoryProperties.DependencyConfig dependency = new RepositoryProperties.DependencyConfig();
        dependency.setId("approved-api");
        dependency.setPath(jar.toString());
        dependency.setSha256(DurableSourceFiles.sha256(jar));
        settings.setDependencies(List.of(dependency));
        return settings;
    }
    private Path jar(String name, String method) throws Exception {
        Path directory = root.resolve(name);
        Files.createDirectories(directory.resolve("dep"));
        Path source = directory.resolve("dep/Api.java");
        Files.writeString(source, "package dep; public class Api { public void " + method + "() {} }", StandardCharsets.UTF_8);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-d", directory.toString(), source.toString())).isZero();
        Path jar = directory.resolve("api.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("dep/Api.class"));
            Files.copy(directory.resolve("dep/Api.class"), output);
            output.closeEntry();
        }
        return jar;
    }
}
