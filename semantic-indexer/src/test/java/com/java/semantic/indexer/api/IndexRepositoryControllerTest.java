package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexRepositoryControllerTest {
    @Test
    void ensure_returns_accepted_job_with_the_resolved_revision() {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryRevision revision = new RepositoryRevision("b".repeat(40));
        IndexJob job = new IndexJob(IndexJobId.create(), RepositoryId.of("orders"), revision, new GenerationId("g-orders"), 1,
                IndexJobPhase.ACCEPTED, true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(service.ensure(RepositoryId.of("orders"))).thenReturn(job);

        ResponseEntity<IndexRepositoryController.IndexJobResponse> response = new IndexRepositoryController(service).ensure("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().revision()).isEqualTo(revision.value());
        assertThat(response.getBody().jobId()).isEqualTo(job.id().value());
    }

    @Test
    void sync_checkout_rebuild_and_rollback_are_all_accepted_as_jobs() {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryId repositoryId = RepositoryId.of("orders");
        IndexJob job = job("c");
        when(service.sync(repositoryId, Optional.of("main"))).thenReturn(job);
        when(service.checkout(repositoryId, "c".repeat(40))).thenReturn(job);
        when(service.rebuild(repositoryId, true)).thenReturn(job);
        PublishedGenerationPointer current = pointer("a", "g-current", "1", "old-current");
        PublishedGenerationPointer rollback = pointer("b", "g-rollback", "2", "old-rollback");
        when(service.rollback(repositoryId, current, rollback)).thenReturn(job);
        IndexRepositoryController controller = new IndexRepositoryController(service);

        assertThat(controller.sync("orders", new SyncIndexRequest("main")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.checkout("orders", new CheckoutIndexRequest("c".repeat(40))).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.rebuild("orders", new RebuildIndexRequest(true)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.rollback("orders", request(current, rollback)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private static RollbackIndexRequest request(PublishedGenerationPointer current, PublishedGenerationPointer rollback) {
        return new RollbackIndexRequest(requestPointer(current), requestPointer(rollback));
    }

    private static GenerationPointerRequest requestPointer(PublishedGenerationPointer pointer) {
        return new GenerationPointerRequest(pointer.revision().value(), pointer.generationId().value(), pointer.manifestDigest().value(),
                pointer.committedJobId(), pointer.publishedAt());
    }

    private static PublishedGenerationPointer pointer(String revision, String generationId, String digest, String committedJobId) {
        return new PublishedGenerationPointer(new RepositoryRevision(revision.repeat(40)), new GenerationId(generationId),
                new ManifestDigest(digest.repeat(64)), committedJobId, Instant.parse("2026-08-22T00:00:00Z"));
    }

    private static IndexJob job(String revision) {
        return new IndexJob(IndexJobId.create(), RepositoryId.of("orders"), new RepositoryRevision(revision.repeat(40)), new GenerationId("g-orders"), 1,
                IndexJobPhase.ACCEPTED, true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
