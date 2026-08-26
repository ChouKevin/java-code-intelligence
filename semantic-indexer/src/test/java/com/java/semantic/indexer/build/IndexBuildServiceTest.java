package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexPublicationIntent;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.indexer.uat.PublicationGate;
import com.java.semantic.indexer.uat.UatPublicationGate;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexBuildServiceTest {

    @Test
    void propagates_exact_repository_checkout_failure_without_mutating_job_state() {
        PublicationGate gate = mock(PublicationGate.class);
        IndexBuildService service = new IndexBuildService(
                mock(FullIndexPlanner.class),
                mock(RepositoryIndexExporter.class),
                mock(MongoGenerationWriter.class),
                mock(SourceIndexBatchDocumentMapper.class),
                mock(GenerationValidator.class),
                ignored -> { throw new RepositoryMutationException("checkout failed"); },
                mock(IncrementalGenerationBuilder.class), mock(IndexJobStore.class), mock(PublicationPort.class), gate);
        IndexJob job = job();

        assertThatThrownBy(() -> service.build(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasMessageContaining("checkout failed");
        verify(gate).abortPublication();
    }

    @Test
    void publication_failure_does_not_abort_a_newer_gate_cycle() {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));
        gate.arm();
        gate.release();
        PublicationPort publication = mock(PublicationPort.class);
        when(publication.publish(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            gate.arm();
            throw new PublicationConflictException();
        });
        IndexBuildService service = successfulService(gate, publication);

        assertThatThrownBy(() -> service.build(job()))
                .isInstanceOf(PublicationConflictException.class);

        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(gate::arm);
        gate.release();
    }

    private static IndexBuildService successfulService(PublicationGate gate, PublicationPort publication) {
        IndexJob job = job();
        FullIndexPlanner planner = mock(FullIndexPlanner.class);
        RepositoryIndexExporter exporter = mock(RepositoryIndexExporter.class);
        MongoGenerationWriter generationWriter = mock(MongoGenerationWriter.class);
        SourceIndexBatchDocumentMapper mapper = mock(SourceIndexBatchDocumentMapper.class);
        GenerationValidator validator = mock(GenerationValidator.class);
        IncrementalGenerationBuilder incrementalBuilder = mock(IncrementalGenerationBuilder.class);
        IndexJobStore jobs = mock(IndexJobStore.class);
        FullIndexPlan plan = new FullIndexPlan(Path.of("."), List.of());
        IncrementalGenerationBuilder.BuildSelection selection = new IncrementalGenerationBuilder.BuildSelection(false,
                mock(com.java.semantic.indexer.incremental.IncrementalIndexPlan.class), plan);
        ManifestDigest digest = new ManifestDigest("1".repeat(64));
        GenerationValidator.ValidationResult result = new GenerationValidator.ValidationResult(digest, Map.of(), List.of());
        IndexJobTarget target = job.target().orElseThrow();
        IndexPublicationIntent intent = new IndexPublicationIntent(job.id(), IndexJobOperation.BUILD, job.repositoryId(),
                target.revision(), target.generationId(), digest, Optional.empty(), Optional.empty(), Optional.empty());
        IndexBuildService.CheckedOutRepository checkout = new IndexBuildService.CheckedOutRepository(Path.of("."), target.revision());
        when(planner.plan(checkout.root())).thenReturn(plan);
        when(incrementalBuilder.assemble(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(plan))).thenReturn(selection);
        when(exporter.export(job.repositoryId(), target.revision(), target.generationId(), plan)).thenReturn(List.of());
        when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(target.revision()),
                org.mockito.ArgumentMatchers.eq(target.revision()))).thenReturn(result);
        when(jobs.prepareBuildPublication(job, digest)).thenReturn(Optional.of(intent));
        return new IndexBuildService(planner, exporter, generationWriter, mapper, validator, ignored -> checkout,
                incrementalBuilder, jobs, publication, gate);
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
