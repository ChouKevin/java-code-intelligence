package com.java.semantic.query.application;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.config.SearchAccessPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class ReviewQueryFacadeTest {

    @Test
    void search_selects_one_review_side_and_keeps_filtered_coverage_in_an_empty_result_context() {
        ReviewGenerationSelector selector = mock(ReviewGenerationSelector.class);
        ReviewManifestReadService manifests = mock(ReviewManifestReadService.class);
        SelectedSemanticQueryService selectedQueries = mock(SelectedSemanticQueryService.class);
        SelectedGenerationGuard guard = mock(SelectedGenerationGuard.class);
        SourceIndexCoverageReader coverageReader = mock(SourceIndexCoverageReader.class);
        SelectedGeneration selected = new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision("a".repeat(40)),
                new GenerationId("review-a"), new ManifestDigest("b".repeat(64)));
        ReviewManifestDocument manifest = mock(ReviewManifestDocument.class);
        when(manifest.reviewId()).thenReturn(new ReviewId("review-fixture"));
        SealedGeneration sealed = mock(SealedGeneration.class);
        when(sealed.analysisEvidence()).thenReturn(evidence());
        com.java.semantic.model.review.ReviewEndpoint endpoint = mock(com.java.semantic.model.review.ReviewEndpoint.class);
        when(endpoint.generation()).thenReturn(sealed);
        when(manifest.a()).thenReturn(Optional.of(endpoint));
        when(selector.select(any(), any(), any(), any(), any())).thenReturn(new ReviewSelection(manifest, ReviewSide.A, selected));
        when(guard.searchAccessPlan("orders")).thenReturn(mock(SearchAccessPlan.class));
        when(coverageReader.coverage(any(), any(), any(), any())).thenReturn(new SourceIndexCoverage(2,
                List.of(new SourceIndexIssue("src/Order.java", "PARSE_ERROR"))));
        SemanticQueryContract.SearchCodeResult empty = new SemanticQueryContract.SearchCodeResult("orders", "a".repeat(40), List.of(),
                new SemanticQueryContract.Page(0, 20, 0, 0, false), new SemanticQueryContract.SourceCoverage(2, 1, List.of("PARSE_ERROR")));
        when(selectedQueries.searchCode(any(), any())).thenReturn(empty);
        ReviewQueryFacade facade = new ReviewQueryFacade(selector, manifests, selectedQueries, guard, coverageReader);

        ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> result = facade.searchCode(
                new ReviewQueryContract.ReviewSearchCodeRequest("orders", "review-fixture", ReviewSide.A, "a".repeat(40),
                        "Order", Set.of(), Optional.empty(), 0, 20));

        assertThat(result.result().items()).isEmpty();
        assertThat(result.context().generationId()).isEqualTo("review-a");
        assertThat(result.context().coverage().sourceCoverage()).isEqualTo(new SemanticQueryContract.SourceCoverage(2, 1,
                List.of("PARSE_ERROR")));
        assertThat(result.context().coverage().semanticLimitations()).containsExactly("PARSE_ERROR");
        verify(selector, times(1)).select(any(), any(), any(), any(), any());
    }

    @Test
    void get_review_and_side_context_include_only_safe_authorized_persisted_limitations() {
        ReviewGenerationSelector selector = mock(ReviewGenerationSelector.class);
        ReviewManifestReadService manifests = mock(ReviewManifestReadService.class);
        SelectedSemanticQueryService selectedQueries = mock(SelectedSemanticQueryService.class);
        SelectedGenerationGuard guard = mock(SelectedGenerationGuard.class);
        SourceIndexCoverageReader coverageReader = mock(SourceIndexCoverageReader.class);
        SelectedGeneration selectedA = selected("review-a", "a");
        SelectedGeneration selectedB = selected("review-b", "c");
        SemanticAnalysisEvidence evidence = evidence(new SemanticAnalysisEvidence.Limitation("BUILD_WITH_ERROR", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("SOURCE_ALLOWED", Optional.of("src/Allowed.java")),
                new SemanticAnalysisEvidence.Limitation("SOURCE_DENIED", Optional.of("src/Denied.java")));
        ReviewManifestDocument manifest = mock(ReviewManifestDocument.class);
        com.java.semantic.model.review.ReviewEndpoint endpointA = endpoint(selectedA, evidence, "snapshot-a");
        com.java.semantic.model.review.ReviewEndpoint endpointB = endpoint(selectedB, evidence, "snapshot-b");
        when(manifest.reviewId()).thenReturn(new ReviewId("review-fixture"));
        when(manifest.a()).thenReturn(Optional.of(endpointA));
        when(manifest.b()).thenReturn(Optional.of(endpointB));
        when(manifests.requireReady(any(), any())).thenReturn(manifest);
        when(selector.select(any(), any(), eq(ReviewSide.A), any(), any())).thenReturn(new ReviewSelection(manifest, ReviewSide.A, selectedA));
        when(selector.select(any(), any(), eq(ReviewSide.B), any(), any())).thenReturn(new ReviewSelection(manifest, ReviewSide.B, selectedB));
        SearchAccessPlan accessPlan = mock(SearchAccessPlan.class);
        when(guard.searchAccessPlan("orders")).thenReturn(accessPlan);
        when(coverageReader.coverage(any(), any(), any(), eq(Optional.empty()))).thenReturn(new SourceIndexCoverage(2, List.of()));
        when(coverageReader.coverage(any(), any(), any(), eq(Optional.of("src/Allowed.java")))).thenReturn(new SourceIndexCoverage(1, List.of()));
        when(coverageReader.coverage(any(), any(), any(), eq(Optional.of("src/Denied.java")))).thenReturn(new SourceIndexCoverage(0, List.of()));
        configureDiscovery(manifest);
        SemanticQueryContract.SearchCodeResult empty = new SemanticQueryContract.SearchCodeResult("orders", "a".repeat(40), List.of(),
                new SemanticQueryContract.Page(0, 20, 0, 0, false), new SemanticQueryContract.SourceCoverage(2, 0, List.of()));
        when(selectedQueries.searchCode(any(), any())).thenReturn(empty);
        ReviewQueryFacade facade = new ReviewQueryFacade(selector, manifests, selectedQueries, guard, coverageReader);

        ReviewQueryContract.ReviewDetails details = facade.getReview(new ReviewQueryContract.ReviewRequest("orders", "review-fixture"));
        ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> side = facade.searchCode(
                new ReviewQueryContract.ReviewSearchCodeRequest("orders", "review-fixture", ReviewSide.A, "a".repeat(40),
                        "Order", Set.of(), Optional.empty(), 0, 20));

        assertThat(details.a().coverage().semanticLimitations()).containsExactly("BUILD_WITH_ERROR", "SOURCE_ALLOWED");
        assertThat(details.b().coverage().semanticLimitations()).containsExactly("BUILD_WITH_ERROR", "SOURCE_ALLOWED");
        assertThat(side.context().coverage().semanticLimitations()).containsExactly("BUILD_WITH_ERROR", "SOURCE_ALLOWED");
    }

    private static SelectedGeneration selected(String generationId, String revisionCharacter) {
        return new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision(revisionCharacter.repeat(40)),
                new GenerationId(generationId), new ManifestDigest("b".repeat(64)));
    }

    private static SemanticAnalysisEvidence evidence(SemanticAnalysisEvidence.Limitation... limitations) {
        return new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "d".repeat(64), "SUCCESS", List.of(),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of(limitations));
    }

    private static com.java.semantic.model.review.ReviewEndpoint endpoint(SelectedGeneration selected,
                                                                           SemanticAnalysisEvidence evidence, String snapshotId) {
        SealedGeneration generation = mock(SealedGeneration.class);
        when(generation.selected()).thenReturn(selected);
        when(generation.analysisEvidence()).thenReturn(evidence);
        com.java.semantic.model.index.AnalysisFingerprint fingerprint =
                mock(com.java.semantic.model.index.AnalysisFingerprint.class);
        when(fingerprint.digest()).thenReturn("e".repeat(64));
        when(generation.fingerprint()).thenReturn(fingerprint);
        com.java.semantic.model.git.GitSnapshotId snapshot = mock(com.java.semantic.model.git.GitSnapshotId.class);
        when(snapshot.value()).thenReturn(snapshotId);
        com.java.semantic.model.review.ReviewEndpoint endpoint = mock(com.java.semantic.model.review.ReviewEndpoint.class);
        when(endpoint.generation()).thenReturn(generation);
        when(endpoint.snapshotId()).thenReturn(snapshot);
        return endpoint;
    }

    private static void configureDiscovery(ReviewManifestDocument manifest) {
        com.java.semantic.model.review.CapturedReviewBaseline baseline = mock(com.java.semantic.model.review.CapturedReviewBaseline.class);
        com.java.semantic.model.index.PublishedGenerationPointer pointer = mock(com.java.semantic.model.index.PublishedGenerationPointer.class);
        when(pointer.revision()).thenReturn(new RepositoryRevision("a".repeat(40)));
        when(pointer.generationId()).thenReturn(new GenerationId("baseline"));
        when(pointer.manifestDigest()).thenReturn(new ManifestDigest("b".repeat(64)));
        when(baseline.pointer()).thenReturn(pointer);
        when(baseline.capturedAt()).thenReturn(java.time.Instant.parse("2026-09-19T00:00:00Z"));
        when(manifest.capturedBaseline()).thenReturn(baseline);
        when(manifest.comparisonType()).thenReturn(com.java.semantic.model.review.ReviewComparisonType.CURRENT_TO_COMMIT);
        com.java.semantic.model.git.GitComparisonId comparison = mock(com.java.semantic.model.git.GitComparisonId.class);
        when(comparison.value()).thenReturn("comparison");
        when(manifest.comparisonId()).thenReturn(Optional.of(comparison));
        when(manifest.publishedAt()).thenReturn(Optional.of(java.time.Instant.parse("2026-09-19T00:00:01Z")));
    }
}
