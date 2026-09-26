package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.IndexPublicationState;
import com.java.semantic.indexer.job.ReviewBuildTargets;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.review.CapturedReviewBaseline;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IndexRepositoryControllerTest {
    @Test
    void ensure_returns_accepted_job_with_the_resolved_revision() {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryRevision revision = new RepositoryRevision("b".repeat(40));
        IndexJob job = new IndexJob(IndexJobId.create(), RepositoryId.of("orders"), Optional.of(new IndexJobTarget(revision, new GenerationId("g-orders"), 1L)),
                IndexJobPhase.ACCEPTED, true, Optional.empty(), false, IndexJobOperation.BUILD);
        when(service.ensure(RepositoryId.of("orders"))).thenReturn(job);

        ResponseEntity<IndexRepositoryController.IndexJobResponse> response = new IndexRepositoryController(service).ensure("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().target().revision()).isEqualTo(revision.value());
        assertThat(response.getBody().jobId()).isEqualTo(job.id().value());
    }

    @Test
    void sync_checkout_rebuild_and_rollback_are_all_accepted_as_jobs() {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryId repositoryId = RepositoryId.of("orders");
        IndexJob job = job("c");
        PublishedGenerationPointer current = pointer("a", "g-current", "1", "old-current");
        PublishedGenerationPointer rollback = pointer("b", "g-rollback", "2", "old-rollback");
        when(service.sync(repositoryId, Optional.of("main"))).thenReturn(job);
        when(service.checkout(repositoryId, "c".repeat(40))).thenReturn(job);
        when(service.rebuild(repositoryId, true, current)).thenReturn(job);
        when(service.rollback(repositoryId, current, rollback)).thenReturn(job);
        IndexRepositoryController controller = new IndexRepositoryController(service);

        assertThat(controller.sync("orders", new SyncIndexRequest("main")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.checkout("orders", new CheckoutIndexRequest("c".repeat(40))).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.rebuild("orders", new RebuildIndexRequest(true, requestPointer(current))).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.rollback("orders", request(current, rollback)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void job_returns_the_admin_visible_terminal_state_and_missing_jobs_are_not_found() {
        IndexRequestService service = mock(IndexRequestService.class);
        IndexJob job = job("d");
        when(service.job(job.id())).thenReturn(Optional.of(job));
        PublishedGenerationPointer current = pointer("e", "g-current", "3", "published-current");
        when(service.currentPointer(RepositoryId.of("orders"))).thenReturn(Optional.of(current));
        IndexRepositoryController controller = new IndexRepositoryController(service);

        ResponseEntity<IndexRepositoryController.IndexJobStatusResponse> response = controller.job("orders", job.id().value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().jobId()).isEqualTo(job.id().value());
        assertThat(response.getBody().operation()).isEqualTo(job.operation().name());
        assertThat(response.getBody().active()).isTrue();
        assertThat(response.getBody().currentPointer().generationId()).isEqualTo(current.generationId().value());
        assertThatThrownBy(() -> controller.job("orders", "00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("index job was not found");
        assertThatThrownBy(() -> controller.job("payments", job.id().value()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("index job was not found");
    }

    @Test
    void publication_returns_the_exact_current_and_bounded_rollback_pointers() {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryId repositoryId = RepositoryId.of("orders");
        PublishedGenerationPointer current = pointer("e", "g-current", "3", "published-current");
        PublishedGenerationPointer rollback = pointer("f", "g-rollback", "4", "published-rollback");
        when(service.publicationState(repositoryId)).thenReturn(Optional.of(
                new IndexPublicationState(Optional.of(current), Optional.of(rollback))));
        IndexRepositoryController controller = new IndexRepositoryController(service);

        ResponseEntity<IndexRepositoryController.IndexPublicationResponse> response = controller.publication("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currentPointer().committedJobId()).isEqualTo("published-current");
        assertThat(response.getBody().rollbackPointer().committedJobId()).isEqualTo("published-rollback");
        assertThatThrownBy(() -> controller.publication("missing"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("repository publication was not found");
    }

    @Test
    void comparison_admission_rejects_missing_null_and_invalid_shas_at_the_http_boundary_and_accepts_exact_payload() throws Exception {
        IndexRequestService service = mock(IndexRequestService.class);
        IndexJob comparison = new IndexJob(IndexJobId.create(), RepositoryId.of("orders"), Optional.empty(), IndexJobPhase.ACCEPTED, true,
                Optional.empty(), false, IndexJobOperation.GIT_COMPARISON, Optional.of(com.java.semantic.indexer.job.GitEvidenceJob.comparison(
                        new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40)))));
        String previous = "a".repeat(40);
        String current = "b".repeat(40);
        when(service.prepareGitComparison(RepositoryId.of("orders"), previous, current)).thenReturn(comparison);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IndexRepositoryController(service)).build();

        mvc.perform(post("/index/repositories/orders/git/comparisons").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/index/repositories/orders/git/comparisons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previous\":null,\"current\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/index/repositories/orders/git/comparisons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previous\":\"bad\",\"current\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/index/repositories/orders/git/comparisons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previous\":\"" + previous + "\",\"current\":\"" + current + "\"}"))
                .andExpect(status().isAccepted());

        verify(service).prepareGitComparison(RepositoryId.of("orders"), previous, current);
    }

    @Test
    void history_admission_rejects_invalid_catalog_and_revision_before_job_admission_and_accepts_exact_payload() throws Exception {
        IndexRequestService service = mock(IndexRequestService.class);
        IndexJob history = mock(IndexJob.class);
        when(history.id()).thenReturn(IndexJobId.create());
        when(history.repositoryId()).thenReturn(RepositoryId.of("orders"));
        when(history.target()).thenReturn(Optional.empty());
        when(history.phase()).thenReturn(IndexJobPhase.ACCEPTED);
        when(history.failureCategory()).thenReturn(Optional.empty());
        String catalogId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String revision = "a".repeat(40);
        when(service.prepareGitHistory(RepositoryId.of("orders"), catalogId, "main", revision)).thenReturn(history);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IndexRepositoryController(service)).build();

        mvc.perform(post("/index/repositories/orders/git/history").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogId\":\"bad\",\"branch\":\"main\",\"revision\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).prepareGitHistory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        mvc.perform(post("/index/repositories/orders/git/history").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogId\":\"" + catalogId + "\",\"branch\":\"main\",\"revision\":\"" + revision + "\"}"))
                .andExpect(status().isAccepted());

        verify(service).prepareGitHistory(RepositoryId.of("orders"), catalogId, "main", revision);
    }

    @Test
    void review_admission_and_status_expose_nested_comparison_type_without_changing_build_responses() throws Exception {
        IndexRequestService service = mock(IndexRequestService.class);
        RepositoryId repositoryId = RepositoryId.of("orders");
        String revision = "b".repeat(40);
        PublishedGenerationPointer baseline = pointer("a", "g-baseline", "1", "baseline-job");
        ReviewJobPayload payload = new ReviewJobPayload(new ReviewId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                new CapturedReviewBaseline(baseline, Instant.parse("2026-08-22T00:00:00Z")), new RepositoryRevision(revision),
                new ReviewBuildTargets(new IndexJobTarget(baseline.revision(), new GenerationId("g-a"), 2L),
                        new IndexJobTarget(new RepositoryRevision(revision), new GenerationId("g-b"), 3L)),
                ReviewPreparationStage.PREPARING_A, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        IndexJob review = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.ACCEPTED, true,
                Optional.empty(), false, IndexJobOperation.REVIEW, Optional.empty(), Optional.of(payload));
        IndexJob build = job("c");
        when(service.ensure(repositoryId)).thenReturn(build);
        when(service.review(repositoryId, new RepositoryRevision(revision))).thenReturn(review);
        when(service.job(review.id())).thenReturn(Optional.of(review));
        when(service.job(build.id())).thenReturn(Optional.of(build));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IndexRepositoryController(service)).build();

        mvc.perform(post("/index/repositories/orders/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":\"" + revision + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.review.comparisonType").value("CURRENT_TO_COMMIT"))
                .andExpect(jsonPath("$.review.reviewId").value(payload.reviewId().value()))
                .andExpect(jsonPath("$.comparisonType").doesNotExist());
        mvc.perform(get("/index/repositories/orders/jobs/{jobId}", review.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("REVIEW"))
                .andExpect(jsonPath("$.review.comparisonType").value("CURRENT_TO_COMMIT"))
                .andExpect(jsonPath("$.review.reviewId").value(payload.reviewId().value()))
                .andExpect(jsonPath("$.comparisonType").doesNotExist());
        mvc.perform(post("/index/repositories/orders/ensure"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.review").doesNotExist());
        mvc.perform(get("/index/repositories/orders/jobs/{jobId}", build.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("BUILD"))
                .andExpect(jsonPath("$.review").doesNotExist());
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
        return new IndexJob(IndexJobId.create(), RepositoryId.of("orders"),
                Optional.of(new IndexJobTarget(new RepositoryRevision(revision.repeat(40)), new GenerationId("g-orders"), 1L)),
                IndexJobPhase.ACCEPTED, true, Optional.empty(), false, IndexJobOperation.BUILD);
    }
}
