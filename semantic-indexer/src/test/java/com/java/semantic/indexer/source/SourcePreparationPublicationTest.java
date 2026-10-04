package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePreparationPublicationTest {
    @TempDir Path root;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void accepted_request_survives_lost_response_and_branch_move_preserves_exact_unicode_crlf_blob() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            byte[] original = "語言🙂\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);
            String revisionA = fixture.commit("source/空 白.java", original, "A");
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryProperties properties = properties(fixture);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                JGitRevisionExporter exporter = new JGitRevisionExporter(resolver, registry, mapper);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver, exporter, publications, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, publications, registry);
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                SourcePreparationJob admitted = service.prepareSource(fixture.repository, request, Optional.empty());
                assertThat(new FileSourceJobStore(properties, mapper, owner).find(fixture.repository, request)
                        .orElseThrow().jobId()).isEqualTo(admitted.jobId());
                assertThatThrownBy(() -> service.prepareSource(fixture.repository, request,
                        Optional.of(RepositoryRevision.ofSha("a".repeat(40)))))
                        .isInstanceOf(PreparationRequestReusedException.class);
                SourcePreparationJob running = jobs.claimNext().orElseThrow();
                RepositoryRevision pinned = resolver.resolve(fixture.repository, Optional.empty());
                assertThat(pinned.value()).isEqualTo(revisionA);
                running = jobs.recordResolved(fixture.repository, new IndexJobId(running.jobId()), pinned);
                String revisionB = fixture.commit("source/空 白.java", "changed\n".getBytes(StandardCharsets.UTF_8), "B");
                assertThat(revisionB).isNotEqualTo(revisionA);
                SourcePreparationJob finished = manager.execute(running);
                assertThat(finished.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(finished.resolvedRevision()).contains(revisionA);
                assertThat(publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revisionA);
                assertThat(Files.readAllBytes(fixture.published.resolve("orders/revisions").resolve(revisionA)
                        .resolve("tree/source/空 白.java"))).isEqualTo(original);
                assertThat(finished.publication().orElseThrow().manifestDigest())
                        .isEqualTo(publications.state(fixture.repository).current().orElseThrow().manifestDigest());
            }
        }
    }

    @Test
    void public_publication_is_readable_by_distinct_uid_without_exposing_private_jobs() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("source/A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                new SourcePreparationService(jobs, publications, registry)
                        .prepareSource(fixture.repository, request, Optional.empty());
                SourcePreparationJob complete = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(complete.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                new com.java.semantic.indexer.config.ConfiguredRepositoryPublisher(
                        registry, properties, mapper, owner).publish();
                Path revision = fixture.published.resolve("orders/revisions")
                        .resolve(complete.resolvedRevision().orElseThrow());
                assertThat(Files.getPosixFilePermissions(fixture.admin))
                        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                assertThat(Files.getPosixFilePermissions(fixture.admin.resolve("jobs/orders").resolve(request.value() + ".json")))
                        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                for (Path directory : java.util.List.of(fixture.published, fixture.published.resolve("orders"),
                        fixture.published.resolve("orders/revisions"), revision,
                        revision.resolve("tree"), revision.resolve("tree/source"))) {
                    assertThat(Files.getPosixFilePermissions(directory))
                            .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                }
                for (Path file : java.util.List.of(fixture.published.resolve("repositories.json"),
                        fixture.published.resolve("orders/state.json"), revision.resolve("manifest.json"),
                        revision.resolve("inventory.jsonl"), revision.resolve("tree/source/A.java"))) {
                    assertThat(Files.getPosixFilePermissions(file))
                            .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                }
            }
        }
    }

    @Test
    void failed_second_revision_keeps_ready_first_revision_and_second_writer_is_denied() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revisionA = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                assertThatThrownBy(() -> new DurableSourceFiles(fixture.admin)).isInstanceOf(java.io.IOException.class);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore published = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), published, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, published, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob first = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(first.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                SourcePreparationJob next = service.prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.of(RepositoryRevision.ofSha("f".repeat(40))));
                assertThat(next.phase()).isEqualTo(SourcePreparationJob.Phase.ACCEPTED);
                SourcePreparationJob second = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(second.phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(published.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revisionA);
                assertThat(published.state(fixture.repository).published()).containsKey(revisionA);
            }
        }
    }

    @Test
    void delayed_acceptance_never_rewinds_the_same_job_or_overwrites_a_newer_completed_job() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, publications, registry);
                SourcePreparationJob acceptedA = service.prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob runningA = jobs.claimNext().orElseThrow();
                publications.updatePreparation(runningA);
                // The servlet's ACCEPTED state write can lag the dispatcher's durable claim.
                publications.updatePreparation(acceptedA);
                assertThat(publications.state(fixture.repository).preparation().phase())
                        .isEqualTo(com.java.semantic.model.source.SourceRepositoryState.PreparationPhase.RUNNING);
                SourcePreparationJob completedA = manager.execute(runningA);
                assertThat(completedA.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                publications.updatePreparation(acceptedA);
                assertThat(publications.state(fixture.repository).preparation().phase())
                        .isEqualTo(com.java.semantic.model.source.SourceRepositoryState.PreparationPhase.COMPLETE);
                SourcePreparationJob acceptedB = service.prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.of(RepositoryRevision.ofSha(revision)));
                SourcePreparationJob runningB = jobs.claimNext().orElseThrow();
                publications.updatePreparation(runningB);
                SourcePreparationJob completedB = manager.execute(runningB);
                assertThat(completedB.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                // A delayed older admission must not replace B's newer public preparation identity.
                publications.updatePreparation(acceptedA);
                assertThat(publications.state(fixture.repository).preparation().phase())
                        .isEqualTo(com.java.semantic.model.source.SourceRepositoryState.PreparationPhase.COMPLETE);
                assertThat(publications.state(fixture.repository).preparation().jobId())
                        .contains(acceptedB.jobId());
            }
        }
    }

    @Test
    void admission_after_prior_completion_uses_current_publication_even_if_request_arrived_earlier() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, publications, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.empty());
                assertThat(manager.execute(jobs.claimNext().orElseThrow()).phase())
                        .isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                String revisionB = fixture.commit("B.java", "class B {}\n".getBytes(StandardCharsets.UTF_8), "B");
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.of(RepositoryRevision.ofSha(revisionB)));
                SourcePreparationJob runningB = jobs.claimNext().orElseThrow();
                java.util.concurrent.atomic.AtomicReference<SourcePreparationJob> pending =
                        new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread incoming = new Thread(() -> {
                    try {
                        pending.set(service.prepareSource(fixture.repository,
                                new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty()));
                    } catch (Throwable exception) {
                        workerFailure.set(exception);
                    }
                }, "source-admission-before-completion");
                CurrentPublication publishedB;
                synchronized (jobs) {
                    incoming.start();
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                    while (incoming.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                        Thread.sleep(2);
                    }
                    assertThat(incoming.getState()).isEqualTo(Thread.State.BLOCKED);
                    assertThat(manager.execute(runningB).phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                    publishedB = publications.state(fixture.repository).current().orElseThrow();
                }
                incoming.join(10_000);
                assertThat(incoming.isAlive()).isFalse();
                assertThat(workerFailure.get()).isNull();
                assertThat(pending.get()).isNotNull();
                SourcePreparationJob afterB = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(afterB.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(afterB.expectedCurrent()).contains(publishedB);
                assertThat(afterB.publication().orElseThrow().context().revision()).isEqualTo(revisionB);
            }
        }
    }

    @Test
    void cross_filesystem_atomic_rename_failure_never_publishes_membership() throws Exception {
        Path memory = Path.of("/dev/shm");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isWritable(memory)
                && !Files.getFileStore(memory).equals(Files.getFileStore(root)));
        Path separate = Files.createTempDirectory(memory, "source-publication-");
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revision = fixture.commit("Source.java", "class Source {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = properties(fixture);
            properties.setSourcePublishedRoot(separate.toString());
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore published = new SourcePublicationStore(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                jobs.admit(fixture.repository, request,
                        Optional.of(RepositoryRevision.ofSha(revision)), "main", registry.origin(fixture.repository),
                        Optional.empty());
                SourcePreparationJob claimed = jobs.claimNext().orElseThrow();
                SourcePreparationJob pinned = jobs.recordResolved(fixture.repository, new IndexJobId(claimed.jobId()),
                        resolver.resolve(fixture.repository, Optional.of(RepositoryRevision.ofSha(revision))));
                Path staging = fixture.admin.resolve("staging").resolve(pinned.jobId());
                Files.createDirectories(staging);
                com.java.semantic.model.source.SourceRevisionManifest manifest =
                        new JGitRevisionExporter(resolver, registry, mapper).export(
                                fixture.repository, RepositoryRevision.ofSha(revision), staging);
                assertThatThrownBy(() -> published.seal(pinned, staging, manifest))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(published.state(fixture.repository).current()).isEmpty();
                assertThat(published.state(fixture.repository).published()).isEmpty();
                assertThat(Files.exists(separate.resolve("orders/revisions").resolve(revision))).isFalse();
            }
        } finally {
            Files.walkFileTree(separate, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path directory, java.io.IOException failure)
                        throws java.io.IOException {
                    Files.delete(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void oversized_registry_does_not_replace_previously_committed_public_metadata() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            RepositoryProperties properties = properties(fixture);
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                new com.java.semantic.indexer.config.ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(properties), properties, mapper, writer).publish();
                Path registryFile = fixture.published.resolve("repositories.json");
                byte[] committed = Files.readAllBytes(registryFile);
                Map<String, RepositoryProperties.RepositoryConfig> many = new java.util.LinkedHashMap<>();
                for (int index = 0; index < 3000; index++) {
                    RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
                    config.setDisplayName("x".repeat(512));
                    config.setDefaultBranch("main");
                    config.setUrl(fixture.remote.toUri().toString());
                    many.put("repo-" + index, config);
                }
                properties.setRepositories(many);
                assertThatThrownBy(() -> new com.java.semantic.indexer.config.ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(properties), properties, mapper, writer).publish())
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Files.readAllBytes(registryFile)).isEqualTo(committed);
            }
        }
    }

    static RepositoryProperties properties(LocalSourceFixture fixture) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setSourceAdminRoot(fixture.admin.toString());
        properties.setSourcePublishedRoot(fixture.published.toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl(fixture.remote.toUri().toString());
        config.setDisplayName("Orders");
        config.setDefaultBranch("main");
        config.setProjectGuidePath("GUIDE.md");
        properties.setRepositories(Map.of("orders", config));
        return properties;
    }
}
