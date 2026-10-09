package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.model.source.PreparedRevision;
import java.nio.file.Files;
import java.time.Instant;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalCurrentPolicyTest {
    @TempDir Path root;

    @Test
    void preparing_historical_sha_preserves_default_branch_current_and_publishes_exact_history() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String history = fixture.commit("A.java", "class A { int n = 1; }\n".getBytes(StandardCharsets.UTF_8), "A");
            String latest = fixture.commit("A.java", "class A { int n = 2; }\n".getBytes(StandardCharsets.UTF_8), "B");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = SourceLifecycleFixture.publications(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, publications, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob refreshed = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(refreshed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                SourceRepositoryState before = publications.state(fixture.repository);
                assertThat(before.current().orElseThrow().revision()).isEqualTo(latest);
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                service.prepareSource(fixture.repository, request, Optional.of(RepositoryRevision.ofSha(history)));
                SourcePreparationJob prepared = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(prepared.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(publications.state(fixture.repository).current()).isEqualTo(before.current());
                assertThat(publications.state(fixture.repository).published()).containsKeys(history, latest);
                assertThat(jobs.find(fixture.repository, request).orElseThrow().resolvedRevision()).contains(history);
            }
        }
    }

    @Test
    void history_without_current_is_readable_and_reuse_preserves_receipt_and_retirement() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = fixture.commit("class A {}\n");
            Instant before = Instant.now();
            fixture.prepareHistorical(revision);
            assertThat(fixture.state().current()).isEmpty();
            PreparedRevision receipt = fixture.state().published().get(revision);
            assertThat(fixture.publications.manifest(receipt.context(), receipt.manifestDigest()).context()).isEqualTo(receipt.context());
            Instant retired = fixture.retention.reconcile(fixture.git.repository, fixture.state(), Instant.now()).get(revision);
            assertThat(retired).isAfterOrEqualTo(before);
            fixture.prepareHistorical(revision);
            assertThat(fixture.state().current()).isEmpty();
            assertThat(fixture.state().published().get(revision)).isEqualTo(receipt);
            assertThat(fixture.retention.reconcile(fixture.git.repository, fixture.state(), Instant.now())).containsEntry(revision, retired);
        }
    }

    @Test
    void historical_reuse_recovers_only_its_own_complete_marker_after_terminal_write_failure() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("class A {}\n");
            fixture.prepareHistorical(a);
            String b = fixture.commit("class B {}\n");
            fixture.prepare(b);
            SourceRepositoryState before = fixture.state();
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            fixture.service.prepareSource(fixture.git.repository, request, Optional.of(RepositoryRevision.ofSha(a)));
            SourcePreparationJob running = fixture.jobs.claimNext().orElseThrow();
            running = fixture.jobs.recordResolved(fixture.git.repository, new IndexJobId(running.jobId()), RepositoryRevision.ofSha(a));
            assertThat(fixture.publications.lookupPublishedJob(running)).isEmpty();
            PreparedRevision old = before.published().get(a);
            fixture.publications.publish(running, fixture.publications.manifest(old.context(), old.manifestDigest()));
            Path record = fixture.git.admin.resolve("jobs/orders").resolve(request.value() + ".json");
            byte[] durableRunning = Files.readAllBytes(record);
            Files.delete(record);
            Files.createDirectory(record);
            SourcePreparationJob committedJob = running;
            assertThatThrownBy(() -> fixture.jobs.complete(fixture.git.repository, new IndexJobId(committedJob.jobId()), old))
                    .isInstanceOf(IllegalStateException.class);
            Files.delete(record);
            Files.write(record, durableRunning);
            FileSourceJobStore reopened = new FileSourceJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.publications);
            SourcePreparationJob recovered = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(recovered.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(recovered.resolvedRevision()).contains(a);
            assertThat(recovered.publication()).contains(old);
            assertThat(fixture.state().current()).isEqualTo(before.current());
            assertThat(fixture.state().published()).isEqualTo(before.published());
            assertThat(reopened.claimNext()).isEmpty();
        }
    }

    @Test
    void accepted_explicit_intent_survives_upgrade_with_original_identity_but_no_current() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = fixture.commit("class A {}\n");
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            SourcePreparationJob accepted = fixture.service.prepareSource(fixture.git.repository, request,
                    Optional.of(RepositoryRevision.ofSha(revision)));
            fixture.jobs.recover(fixture.publications);
            SourcePreparationJob completed = fixture.manager.execute(fixture.jobs.claimNext().orElseThrow());
            assertThat(completed.jobId()).isEqualTo(accepted.jobId());
            assertThat(completed.requestId()).isEqualTo(request.value());
            assertThat(completed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(fixture.state().current()).isEmpty();
            assertThat(fixture.state().published()).containsKey(revision);
        }
    }
}
