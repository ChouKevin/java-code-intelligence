package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobWorker;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RepositoryFence;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IndexBuildServiceTest {

    @Test
    void should_report_source_unavailable_when_exact_repository_checkout_fails() {
        IndexJobWorker worker = mock(IndexJobWorker.class);
        IndexBuildService service = new IndexBuildService(
                mock(FullIndexPlanner.class),
                mock(RepositoryIndexExporter.class),
                mock(MongoGenerationWriter.class),
                mock(SourceIndexBatchDocumentMapper.class),
                mock(GenerationValidator.class),
                worker,
                ignored -> { throw new RepositoryMutationException("checkout failed"); },
                mock(IncrementalGenerationBuilder.class));
        IndexJob job = job();

        assertThatThrownBy(() -> service.build(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasMessageContaining("checkout failed");

        verify(worker).failOrCancel(job, IndexFailureCategory.SOURCE_UNAVAILABLE);
    }

    private static IndexJob job() {
        return new IndexJob(
                new IndexJobId("job-1"),
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha("a".repeat(40)),
                new GenerationId("generation-1"),
                1,
                IndexJobPhase.CHECKOUT,
                true,
                Optional.of("worker-1"),
                Optional.of(new RepositoryFence(1)),
                Optional.of(Instant.parse("2026-08-25T00:00:00Z")),
                Optional.empty());
    }
}
