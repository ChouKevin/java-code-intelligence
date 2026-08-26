package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class IndexBuildServiceTest {

    @Test
    void propagates_exact_repository_checkout_failure_without_mutating_job_state() {
        IndexBuildService service = new IndexBuildService(
                mock(FullIndexPlanner.class),
                mock(RepositoryIndexExporter.class),
                mock(MongoGenerationWriter.class),
                mock(SourceIndexBatchDocumentMapper.class),
                mock(GenerationValidator.class),
                ignored -> { throw new RepositoryMutationException("checkout failed"); },
                mock(IncrementalGenerationBuilder.class), mock(IndexJobStore.class), mock(PublicationPort.class));
        IndexJob job = job();

        assertThatThrownBy(() -> service.build(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasMessageContaining("checkout failed");
    }

    private static IndexJob job() {
        return new IndexJob(
                new IndexJobId("job-1"),
                RepositoryId.of("orders"),
                Optional.of(new IndexJobTarget(RepositoryRevision.ofSha("a".repeat(40)), new GenerationId("generation-1"), 1L)),
                IndexJobPhase.RUNNING,
                true,
                Optional.empty(), false, IndexJobOperation.BUILD);
    }
}
