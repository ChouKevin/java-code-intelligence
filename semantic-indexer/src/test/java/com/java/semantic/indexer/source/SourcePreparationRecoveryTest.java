package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobDispatcher;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePreparationRecoveryTest {

    @Test
    void failed_running_status_write_converges_after_reopen_without_reexecuting_job() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            Path stateFile = fixture.published.resolve("orders/state.json");
            byte[] acceptedState;
            String jobId;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                SourcePreparationService service = new SourcePreparationService(jobs, published, registry);
                SourcePreparationJob accepted = service.prepareSource(fixture.repository, request, Optional.empty());
                jobId = accepted.jobId();
                acceptedState = Files.readAllBytes(stateFile);
                Files.delete(stateFile);
                Files.createDirectory(stateFile);
                Files.writeString(stateFile.resolve("blocker"), "not an atomic state replacement");
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), published, registry, properties);
                IndexJobDispatcher dispatcher = new IndexJobDispatcher(jobs, new IndexJobExecutor(manager), published, SourceLifecycleFixture.collector(properties, mapper, published));
                try {
                    assertThatThrownBy(dispatcher::dispatchOnce).isInstanceOf(IllegalStateException.class);
                    assertThat(jobs.find(fixture.repository, request).orElseThrow().phase())
                            .isEqualTo(SourcePreparationJob.Phase.FAILED);
                    assertThat(Files.exists(fixture.admin.resolve("staging").resolve(jobId))).isFalse();
                } finally {
                    Files.delete(stateFile.resolve("blocker"));
                    Files.delete(stateFile);
                    Files.write(stateFile, acceptedState);
                    dispatcher.stop();
                }
            }
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                jobs.recover(published);
                assertThat(jobs.find(fixture.repository, request).orElseThrow().phase())
                        .isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(published.state(fixture.repository).preparation().phase())
                        .isEqualTo(SourceRepositoryState.PreparationPhase.FAILED);
                assertThat(published.state(fixture.repository).preparation().jobId()).contains(jobId);
                assertThat(published.state(fixture.repository).published()).isEmpty();
                assertThat(jobs.claimNext()).isEmpty();
                assertThat(Files.exists(fixture.admin.resolve("staging").resolve(jobId))).isFalse();
            }
        }
    }

    @Test
    void old_failed_status_does_not_replace_newer_accepted_preparation_on_recovery() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId older = new PreparationRequestId(UUID.randomUUID().toString());
            PreparationRequestId newer = new PreparationRequestId(UUID.randomUUID().toString());
            String newerJobId;
            Path stateFile = fixture.published.resolve("orders/state.json");
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                SourcePreparationService service = new SourcePreparationService(jobs, published, registry);
                service.prepareSource(fixture.repository, older, Optional.empty());
                byte[] acceptedState = Files.readAllBytes(stateFile);
                Files.delete(stateFile);
                Files.createDirectory(stateFile);
                Files.writeString(stateFile.resolve("blocker"), "block replacement");
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), published, registry, properties);
                IndexJobDispatcher dispatcher = new IndexJobDispatcher(jobs, new IndexJobExecutor(manager), published, SourceLifecycleFixture.collector(properties, mapper, published));
                try {
                    assertThatThrownBy(dispatcher::dispatchOnce).isInstanceOf(IllegalStateException.class);
                } finally {
                    Files.delete(stateFile.resolve("blocker"));
                    Files.delete(stateFile);
                    Files.write(stateFile, acceptedState);
                    dispatcher.stop();
                }
                newerJobId = service.prepareSource(fixture.repository, newer, Optional.empty()).jobId();
            }
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                jobs.recover(published);
                assertThat(published.state(fixture.repository).preparation().jobId()).contains(newerJobId);
                assertThat(published.state(fixture.repository).preparation().phase())
                        .isEqualTo(SourceRepositoryState.PreparationPhase.ACCEPTED);
                assertThat(jobs.find(fixture.repository, older).orElseThrow().phase())
                        .isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(jobs.find(fixture.repository, newer).orElseThrow().phase())
                        .isEqualTo(SourcePreparationJob.Phase.ACCEPTED);
            }
        }
    }

    @Test
    void newer_durable_acceptance_remains_claimable_after_reopen_with_reversed_audit_clocks() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId older = new PreparationRequestId(UUID.randomUUID().toString());
            PreparationRequestId newer = new PreparationRequestId(UUID.randomUUID().toString());
            String newerJobId;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                SourcePreparationService service = new SourcePreparationService(jobs, published,
                        new RepositoryRegistry(properties));
                service.prepareSource(fixture.repository, older, Optional.empty());
                SourcePreparationJob claimed = jobs.claimNext().orElseThrow();
                SourcePreparationJob failed = jobs.fail(fixture.repository, new IndexJobId(claimed.jobId()),
                        "PREPARATION_FAILED");
                published.updatePreparation(failed);
                newerJobId = service.prepareSource(fixture.repository, newer, Optional.empty()).jobId();
            }
            rewriteAuditTime(fixture, older, Instant.parse("2040-01-01T00:00:00Z"));
            rewriteAuditTime(fixture, newer, Instant.parse("2000-01-01T00:00:00Z"));
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopened = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopened);
                reopened.recover(published);
                assertThat(reopened.claimNext().orElseThrow().jobId()).isEqualTo(newerJobId);
                assertThat(published.state(fixture.repository).preparation().jobId()).contains(newerJobId);
            }
        }
    }

    @Test
    void latest_terminal_status_wins_recovery_with_reversed_audit_clocks() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId older = new PreparationRequestId(UUID.randomUUID().toString());
            PreparationRequestId newer = new PreparationRequestId(UUID.randomUUID().toString());
            String newerJobId;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                SourcePreparationService service = new SourcePreparationService(jobs, published,
                        new RepositoryRegistry(properties));
                service.prepareSource(fixture.repository, older, Optional.empty());
                SourcePreparationJob first = jobs.claimNext().orElseThrow();
                published.updatePreparation(jobs.fail(fixture.repository, new IndexJobId(first.jobId()),
                        "PREPARATION_FAILED"));
                newerJobId = service.prepareSource(fixture.repository, newer, Optional.empty()).jobId();
                SourcePreparationJob second = jobs.claimNext().orElseThrow();
                jobs.fail(fixture.repository, new IndexJobId(second.jobId()), "PREPARATION_FAILED");
                assertThat(published.state(fixture.repository).preparation().phase())
                        .isEqualTo(SourceRepositoryState.PreparationPhase.ACCEPTED);
            }
            rewriteAuditTime(fixture, older, Instant.parse("2040-01-01T00:00:00Z"));
            rewriteAuditTime(fixture, newer, Instant.parse("2000-01-01T00:00:00Z"));
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopened = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopened);
                reopened.recover(published);
                assertThat(published.state(fixture.repository).preparation().jobId()).contains(newerJobId);
                assertThat(published.state(fixture.repository).preparation().phase())
                        .isEqualTo(SourceRepositoryState.PreparationPhase.FAILED);
                assertThat(reopened.claimNext()).isEmpty();
            }
        }
    }

    @Test
    void interrupted_private_order_reservation_does_not_hide_durable_acceptance() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            String acceptedId;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                acceptedId = new SourcePreparationService(jobs, published, new RepositoryRegistry(properties))
                        .prepareSource(fixture.repository, request, Optional.empty()).jobId();
            }
            // Reservation 2 was durable, but its corresponding acceptance never committed.
            Files.writeString(fixture.admin.resolve("jobs/orders/admission-sequence"), "2");
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                jobs.recover(published);
                assertThat(jobs.claimNext().orElseThrow().jobId()).isEqualTo(acceptedId);
                SourcePreparationJob failed = jobs.fail(fixture.repository, new IndexJobId(acceptedId),
                        "PREPARATION_FAILED");
                published.updatePreparation(failed);
                PreparationRequestId next = new PreparationRequestId(UUID.randomUUID().toString());
                String nextId = new SourcePreparationService(jobs, published, new RepositoryRegistry(properties))
                        .prepareSource(fixture.repository, next, Optional.empty()).jobId();
                assertThat(jobs.claimNext().orElseThrow().jobId()).isEqualTo(nextId);
            }
        }
    }

    @Test
    void missing_private_order_refuses_recovery_instead_of_guessing_from_audit_time() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                new SourcePreparationService(jobs, published, new RepositoryRegistry(properties))
                        .prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                                Optional.empty());
            }
            Files.delete(fixture.admin.resolve("jobs/orders/admission-sequence"));
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopened = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopened);
                assertThatThrownBy(() -> reopened.recover(published)).isInstanceOf(IllegalStateException.class);
                assertThat(published.state(fixture.repository).preparation().phase())
                        .isEqualTo(SourceRepositoryState.PreparationPhase.ACCEPTED);
            }
        }
    }

    @Test
    void duplicate_private_admission_order_refuses_recovery() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId older = new PreparationRequestId(UUID.randomUUID().toString());
            PreparationRequestId newer = new PreparationRequestId(UUID.randomUUID().toString());
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                SourcePreparationService service = new SourcePreparationService(jobs, published,
                        new RepositoryRegistry(properties));
                service.prepareSource(fixture.repository, older, Optional.empty());
                SourcePreparationJob claimed = jobs.claimNext().orElseThrow();
                published.updatePreparation(jobs.fail(fixture.repository, new IndexJobId(claimed.jobId()),
                        "PREPARATION_FAILED"));
                service.prepareSource(fixture.repository, newer, Optional.empty());
            }
            Path olderFile = fixture.admin.resolve("jobs/orders").resolve(older.value() + ".json");
            Path newerFile = fixture.admin.resolve("jobs/orders").resolve(newer.value() + ".json");
            DurableJob first = mapper.readValue(Files.readAllBytes(olderFile), DurableJob.class);
            DurableJob second = mapper.readValue(Files.readAllBytes(newerFile), DurableJob.class);
            Files.write(newerFile, mapper.writeValueAsBytes(new DurableJob(first.sequence(), second.job())));
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopened = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopened);
                assertThatThrownBy(() -> reopened.recover(published)).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    private record DurableJob(long sequence, SourcePreparationJob job) { }

    private void rewriteAuditTime(LocalSourceFixture fixture, PreparationRequestId request, Instant timestamp)
            throws Exception {
        Path record = fixture.admin.resolve("jobs/orders").resolve(request.value() + ".json");
        DurableJob stored = mapper.readValue(Files.readAllBytes(record), DurableJob.class);
        SourcePreparationJob job = stored.job();
        SourcePreparationJob shifted = new SourcePreparationJob(job.formatVersion(), job.jobId(), job.repositoryId(),
                job.requestId(), job.requestedRevision(), job.defaultBranch(), job.originFingerprint(), job.phase(),
                timestamp, job.resolvedRevision(), job.expectedCurrent(), job.publication(), job.failureCode());
        Files.write(record, mapper.writeValueAsBytes(new DurableJob(stored.sequence(), shifted)));
    }

    @TempDir Path root;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void sealed_orphan_is_denied_and_interrupted_job_is_not_refetched() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revisionA = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            String requestB;
            String revisionB;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore published = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                JGitRevisionExporter exporter = new JGitRevisionExporter(resolver, registry, mapper);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver, exporter, published, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, published, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                assertThat(manager.execute(jobs.claimNext().orElseThrow()).phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                revisionB = fixture.commit("B.java", "class B {}\n".getBytes(StandardCharsets.UTF_8), "B");
                PreparationRequestId secondRequest = new PreparationRequestId(UUID.randomUUID().toString());
                requestB = secondRequest.value();
                service.prepareSource(fixture.repository, secondRequest, Optional.of(RepositoryRevision.ofSha(revisionB)));
                SourcePreparationJob running = jobs.claimNext().orElseThrow();
                running = jobs.recordResolved(fixture.repository, new IndexJobId(running.jobId()),
                        resolver.resolve(fixture.repository, Optional.of(RepositoryRevision.ofSha(revisionB))));
                Path staging = fixture.admin.resolve("staging").resolve(running.jobId());
                Files.createDirectories(staging);
                SourceRevisionManifest manifest = exporter.export(fixture.repository, RepositoryRevision.ofSha(revisionB), staging);
                published.seal(running, staging, manifest);
                SourceRepositoryState before = published.state(fixture.repository);
                assertThat(before.current().orElseThrow().revision()).isEqualTo(revisionA);
                assertThat(before.published()).doesNotContainKey(revisionB);
            }
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopenedJobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore recoveredState = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopenedJobs);
                reopenedJobs.recover(recoveredState);
                assertThat(reopenedJobs.find(fixture.repository, new PreparationRequestId(requestB)).orElseThrow().phase())
                        .isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(recoveredState.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revisionA);
                assertThat(recoveredState.state(fixture.repository).published()).doesNotContainKey(revisionB);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                SourcePreparationService service = new SourcePreparationService(reopenedJobs, recoveredState, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.of(RepositoryRevision.ofSha(revisionB)));
                RepositorySourceManager manager = new RepositorySourceManager(reopenedJobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), recoveredState, registry, properties);
                assertThat(manager.execute(reopenedJobs.claimNext().orElseThrow()).phase())
                        .isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(recoveredState.state(fixture.repository).published()).doesNotContainKey(revisionB);
            }
        }
    }

    @Test
    void committed_state_recovers_running_job_and_republication_retains_immutable_receipt() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revisionA = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            String revisionB;
            String requestB;
            String republicationJobId;
            PreparedRevision receiptA;
            byte[] originalManifestA;
            byte[] originalTreeA;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                JGitRevisionExporter exporter = new JGitRevisionExporter(resolver, registry, mapper);
                SourcePreparationService service = new SourcePreparationService(jobs, store, registry);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver, exporter, store, registry, properties);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                manager.execute(jobs.claimNext().orElseThrow());
                receiptA = store.state(fixture.repository).published().get(revisionA);
                Path publicationA = fixture.published.resolve("orders/revisions").resolve(revisionA);
                originalManifestA = Files.readAllBytes(publicationA.resolve("manifest.json"));
                originalTreeA = Files.readAllBytes(publicationA.resolve("tree/A.java"));
                revisionB = fixture.commit("B.java", "class B {}\n".getBytes(StandardCharsets.UTF_8), "B");
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                requestB = request.value();
                service.prepareSource(fixture.repository, request, Optional.of(RepositoryRevision.ofSha(revisionB)));
                SourcePreparationJob second = jobs.claimNext().orElseThrow();
                second = jobs.recordResolved(fixture.repository, new IndexJobId(second.jobId()),
                        resolver.resolve(fixture.repository, Optional.of(RepositoryRevision.ofSha(revisionB))));
                Path staging = fixture.admin.resolve("staging").resolve(second.jobId());
                Files.createDirectories(staging);
                SourceRevisionManifest manifest = exporter.export(fixture.repository, RepositoryRevision.ofSha(revisionB), staging);
                store.seal(second, staging, manifest);
                store.publish(second, manifest);
                // Crash after atomic state replacement, before durable terminal completion.
            }
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                FileSourceJobStore reopenedJobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore recovered = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, reopenedJobs);
                reopenedJobs.recover(recovered);
                assertThat(reopenedJobs.find(fixture.repository, new PreparationRequestId(requestB)).orElseThrow().phase())
                        .isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(recovered.state(fixture.repository).published().keySet()).contains(revisionA, revisionB);
                assertThat(recovered.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revisionB);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(reopenedJobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), recovered, registry, properties);
                SourcePreparationService service = new SourcePreparationService(reopenedJobs, recovered, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                        Optional.of(RepositoryRevision.ofSha(revisionA)));
                SourcePreparationJob republished = manager.execute(reopenedJobs.claimNext().orElseThrow());
                republicationJobId = republished.jobId();
                assertThat(republished.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(recovered.state(fixture.repository).current().orElseThrow().publicationJobId())
                        .isEqualTo(republicationJobId);
                assertThat(recovered.state(fixture.repository).published().get(revisionA)).isEqualTo(receiptA);
                assertThat(republished.publication().orElseThrow()).isEqualTo(receiptA);
                Path publicationA = fixture.published.resolve("orders/revisions").resolve(revisionA);
                assertThat(Files.readAllBytes(publicationA.resolve("manifest.json"))).isEqualTo(originalManifestA);
                assertThat(Files.readAllBytes(publicationA.resolve("tree/A.java"))).isEqualTo(originalTreeA);
            }
        }
    }
}
