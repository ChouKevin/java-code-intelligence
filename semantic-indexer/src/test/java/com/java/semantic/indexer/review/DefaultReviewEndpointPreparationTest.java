package com.java.semantic.indexer.review;

import com.java.semantic.indexer.analysis.AnalysisReuseVerifier;
import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSide;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultReviewEndpointPreparationTest {
    @Test
    void checks_out_the_exact_revision_before_reusing_a_compatible_candidate_without_opening_a_build_scope() {
        IndexJob job = job();
        ExactRepositoryCheckout checkout = mock(ExactRepositoryCheckout.class);
        AnalysisReuseVerifier reuse = mock(AnalysisReuseVerifier.class);
        AtomicBoolean scopeOpened = new AtomicBoolean();
        RepositoryBuildRunner runner = new RepositoryBuildRunner(ignored -> {
            scopeOpened.set(true);
            throw new AssertionError("a compatible endpoint must not seal a new generation");
        });
        SealedGeneration candidate = mock(SealedGeneration.class);
        when(checkout.checkout(job)).thenReturn(checkedOut(job));
        when(reuse.matches(eq(candidate), any())).thenReturn(true);

        SealedGeneration prepared = new DefaultReviewEndpointPreparation(checkout, runner, reuse)
                .prepare(job, ReviewSide.A, List.of(candidate));

        assertThat(prepared).isSameAs(candidate);
        assertThat(scopeOpened).isFalse();
        InOrder ordered = inOrder(checkout, reuse);
        ordered.verify(checkout).checkout(job);
        ordered.verify(reuse).matches(eq(candidate), any());
    }

    @Test
    void seals_the_reserved_target_in_a_closed_scope_after_exact_checkout_rejects_reuse() {
        IndexJob job = job();
        ExactRepositoryCheckout checkout = mock(ExactRepositoryCheckout.class);
        AnalysisReuseVerifier reuse = mock(AnalysisReuseVerifier.class);
        SealedGeneration candidate = mock(SealedGeneration.class);
        SealedGeneration sealedTarget = mock(SealedGeneration.class);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<IndexJob> scopedJob = new AtomicReference<>();
        RepositoryBuildRunner runner = new RepositoryBuildRunner(openedJob -> {
            scopedJob.set(openedJob);
            return scope(sealedTarget, closed);
        });
        when(checkout.checkout(job)).thenReturn(checkedOut(job));
        when(reuse.matches(eq(candidate), any())).thenReturn(false);

        SealedGeneration prepared = new DefaultReviewEndpointPreparation(checkout, runner, reuse)
                .prepare(job, ReviewSide.B, List.of(candidate));

        assertThat(prepared).isSameAs(sealedTarget);
        assertThat(scopedJob.get()).isSameAs(job);
        assertThat(closed).isTrue();
        InOrder ordered = inOrder(checkout, reuse);
        ordered.verify(checkout).checkout(job);
        ordered.verify(reuse).matches(eq(candidate), any());
        verifyNoInteractions(sealedTarget);
    }

    private static RepositoryBuildRunner.BuildScope scope(SealedGeneration sealed, AtomicBoolean closed) {
        return new RepositoryBuildRunner.BuildScope() {
            @Override
            public void build() {
                throw new UnsupportedOperationException("review endpoint preparation seals rather than publishes");
            }

            @Override
            public SealedGeneration seal() {
                return sealed;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static IndexJob job() {
        return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), Optional.of(new IndexJobTarget(
                new RepositoryRevision("a".repeat(40)), new GenerationId("g-1"), 1L)), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.BUILD);
    }

    private static IndexBuildService.CheckedOutRepository checkedOut(IndexJob job) {
        return new IndexBuildService.CheckedOutRepository(Path.of("/work/orders"), job.target().orElseThrow().revision());
    }
}
