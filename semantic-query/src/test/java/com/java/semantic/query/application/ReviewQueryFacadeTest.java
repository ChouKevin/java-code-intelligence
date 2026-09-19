package com.java.semantic.query.application;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.index.SourceIndexIssue;
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
}
