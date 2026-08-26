package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

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

    private static RepositoryBuildRunner.BuildScope scope(Runnable build, Runnable close) {
        return new RepositoryBuildRunner.BuildScope() {
            @Override
            public void build() {
                build.run();
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
