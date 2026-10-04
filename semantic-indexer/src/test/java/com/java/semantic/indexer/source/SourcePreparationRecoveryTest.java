package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SourcePreparationRecoveryTest {
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
                SourcePublicationStore published = new SourcePublicationStore(properties, mapper, writer);
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
                SourcePublicationStore recoveredState = new SourcePublicationStore(properties, mapper, writer);
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
            String receiptA;
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = new SourcePublicationStore(properties, mapper, writer);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                JGitRevisionExporter exporter = new JGitRevisionExporter(resolver, registry, mapper);
                SourcePreparationService service = new SourcePreparationService(jobs, store, registry);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver, exporter, store, registry, properties);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                manager.execute(jobs.claimNext().orElseThrow());
                receiptA = store.state(fixture.repository).published().get(revisionA).firstPublishedJobId();
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
                SourcePublicationStore recovered = new SourcePublicationStore(properties, mapper, writer);
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
                assertThat(recovered.state(fixture.repository).published().get(revisionA).firstPublishedJobId())
                        .isEqualTo(receiptA);
                assertThat(republished.publication().orElseThrow().firstPublishedJobId()).isEqualTo(receiptA);
            }
        }
    }
}
