package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryBuildRunnerTest {
    @Test
    void closes_the_job_scope_after_a_successful_build() {
        AtomicBoolean built = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        RepositoryBuildRunner runner = new RepositoryBuildRunner(job -> scope(() -> built.set(true), () -> closed.set(true)));

        runner.run(job());

        assertThat(built).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void closes_the_job_scope_when_build_fails() {
        AtomicBoolean closed = new AtomicBoolean();
        RuntimeException failure = new RuntimeException("build failed");
        RepositoryBuildRunner runner = new RepositoryBuildRunner(job -> scope(() -> { throw failure; }, () -> closed.set(true)));

        assertThatThrownBy(() -> runner.run(job())).isSameAs(failure);

        assertThat(closed).isTrue();
    }

    @Test
    void seals_in_an_owned_scope_without_publishing_and_closes_the_scope() {
        AtomicBoolean closed = new AtomicBoolean();
        SealedGeneration sealed = Mockito.mock(SealedGeneration.class);
        RepositoryBuildRunner runner = new RepositoryBuildRunner(job -> scope(() -> { }, () -> closed.set(true), sealed));

        assertThat(runner.seal(job())).isSameAs(sealed);

        assertThat(closed).isTrue();
    }

    private static RepositoryBuildRunner.BuildScope scope(Runnable build, Runnable close) {
        return scope(build, close, null);
    }

    private static RepositoryBuildRunner.BuildScope scope(Runnable build, Runnable close,
                                                           SealedGeneration sealed) {
        return new RepositoryBuildRunner.BuildScope() {
            @Override
            public void build() {
                build.run();
            }

            @Override
            public SealedGeneration seal() {
                if (Objects.isNull(sealed)) {
                    throw new UnsupportedOperationException("seal was not configured");
                }
                return sealed;
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }

    private static IndexJob job() {
        return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), Optional.of(new IndexJobTarget(
                new RepositoryRevision("a".repeat(40)), new GenerationId("g-1"), 1L)), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.BUILD);
    }
}
