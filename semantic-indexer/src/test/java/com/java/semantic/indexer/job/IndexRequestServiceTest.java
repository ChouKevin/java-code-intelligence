package com.java.semantic.indexer.job;

import com.java.semantic.indexer.repository.RepositorySourcePort;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexRequestServiceTest {
    @Test
    void ensure_resolves_source_then_returns_an_accepted_job_without_waiting_for_work() {
        RepositorySourcePort source = mock(RepositorySourcePort.class);
        IndexJobStore store = mock(IndexJobStore.class);
        RepositoryId repositoryId = RepositoryId.of("payments");
        RepositoryRevision revision = new RepositoryRevision("a".repeat(40));
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, revision, new GenerationId("g-test"), 1,
                IndexJobPhase.ACCEPTED, true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(source.ensure(repositoryId)).thenReturn(revision);
        when(store.admitEnsure(repositoryId, revision)).thenReturn(job);

        IndexJob result = new IndexRequestService(source, store).ensure(repositoryId);

        assertThat(result).isEqualTo(job);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(store);
        order.verify(store).reconcileCommitted(repositoryId);
        order.verify(store).recoverRevokedClaims(repositoryId);
        order.verify(store).admitEnsure(repositoryId, revision);
    }

    @Test
    void accepts_sync_checkout_rebuild_and_rollback_as_deferred_jobs() {
        RepositorySourcePort source = mock(RepositorySourcePort.class);
        IndexJobStore store = mock(IndexJobStore.class);
        RepositoryId repositoryId = RepositoryId.of("payments");
        RepositoryRevision revision = new RepositoryRevision("b".repeat(40));
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, revision, new GenerationId("g-test"), 1,
                IndexJobPhase.ACCEPTED, true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        PublishedGenerationPointer current = pointer(revision, "g-current", "job-current");
        PublishedGenerationPointer rollback = pointer(revision, "g-rollback", "job-rollback");
        when(source.sync(repositoryId, Optional.of("main"))).thenReturn(revision);
        when(source.checkout(repositoryId, revision.value())).thenReturn(revision);
        when(store.currentRevision(repositoryId)).thenReturn(Optional.of(revision));
        when(store.admit(repositoryId, revision, false)).thenReturn(job);
        when(store.admitRebuild(repositoryId, revision, current)).thenReturn(job);
        when(store.admitRollback(repositoryId, current, rollback)).thenReturn(job);

        IndexRequestService service = new IndexRequestService(source, store);
        assertThat(service.sync(repositoryId, Optional.of("main"))).isEqualTo(job);
        assertThat(service.checkout(repositoryId, revision.value())).isEqualTo(job);
        assertThat(service.rebuild(repositoryId, true, current)).isEqualTo(job);
        assertThat(service.rollback(repositoryId, current, rollback)).isEqualTo(job);
        verify(store).admitRebuild(repositoryId, revision, current);
        verify(store).admitRollback(repositoryId, current, rollback);
    }

    private static PublishedGenerationPointer pointer(RepositoryRevision revision, String generationId, String jobId) {
        return new PublishedGenerationPointer(revision, new GenerationId(generationId), new ManifestDigest("a".repeat(64)), jobId,
                java.time.Instant.parse("2026-08-22T00:00:00Z"));
    }
}
