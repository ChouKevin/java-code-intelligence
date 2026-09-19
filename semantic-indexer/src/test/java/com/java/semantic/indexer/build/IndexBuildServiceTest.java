package com.java.semantic.indexer.build;

import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.analysis.RepositoryAnalysisPreparation;
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
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void exports_the_prepared_plan_without_rediscovering_checkout_sources() {
        IndexJob job = job();
        IndexJobTarget target = job.target().orElseThrow();
        IndexBuildService.CheckedOutRepository checkout = new IndexBuildService.CheckedOutRepository(
                Path.of("prepared-checkout"), target.revision());
        FullIndexPlan preparedPlan = new FullIndexPlan(checkout.root(), List.of(),
                List.of(checkout.root().resolve("src/production/java")),
                Map.of("org.eclipse.jdt.core.compiler.source", "17"));
        PreparedAnalysis preparedAnalysis = mock(PreparedAnalysis.class);
        JavaSemanticService boundSemanticService = mock(JavaSemanticService.class);
        RepositoryAnalysisPreparation preparation = mock(RepositoryAnalysisPreparation.class);
        FullIndexPlanner planner = mock(FullIndexPlanner.class);
        RepositoryIndexExporter exporter = mock(RepositoryIndexExporter.class);
        IncrementalGenerationBuilder incrementalBuilder = mock(IncrementalGenerationBuilder.class);
        MongoGenerationWriter generationWriter = mock(MongoGenerationWriter.class);
        GenerationValidator validator = mock(GenerationValidator.class);
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        PublicationGate gate = mock(PublicationGate.class);
        ManifestDigest digest = new ManifestDigest("1".repeat(64));
        GenerationValidator.ValidationResult validation = new GenerationValidator.ValidationResult(digest, Map.of(), List.of());
        IndexPublicationIntent intent = new IndexPublicationIntent(job.id(), IndexJobOperation.BUILD, job.repositoryId(),
                target.revision(), target.generationId(), digest, Optional.empty(), Optional.empty(), Optional.empty());
        when(preparation.prepare(any())).thenReturn(preparedAnalysis);
        when(preparedAnalysis.plan()).thenReturn(preparedPlan);
        when(preparedAnalysis.semanticService()).thenReturn(boundSemanticService);
        when(incrementalBuilder.assemble(any(), any(), any())).thenReturn(new IncrementalGenerationBuilder.BuildSelection(
                false, mock(com.java.semantic.indexer.incremental.IncrementalIndexPlan.class), preparedPlan));
        when(exporter.export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(preparedAnalysis)))
                .thenReturn(new RepositoryIndexExport(List.of(), mock(com.java.semantic.model.index.SemanticAnalysisEvidence.class)));
        when(validator.validate(any(), org.mockito.ArgumentMatchers.eq(target.revision()),
                org.mockito.ArgumentMatchers.eq(target.revision()))).thenReturn(validation);
        when(jobs.prepareBuildPublication(job, digest)).thenReturn(Optional.of(intent));
        IndexBuildService service = new IndexBuildService(planner, exporter, generationWriter, mock(SourceIndexBatchDocumentMapper.class),
                validator, ignored -> checkout, incrementalBuilder, jobs, publication, gate, preparation);

        service.build(job);

        org.mockito.ArgumentCaptor<com.java.semantic.indexer.analysis.AnalysisTarget> targetCaptor =
                org.mockito.ArgumentCaptor.forClass(com.java.semantic.indexer.analysis.AnalysisTarget.class);
        verify(preparation).prepare(targetCaptor.capture());
        assertThat(targetCaptor.getValue().snapshot().root()).isEqualTo(checkout.root());
        assertThat(targetCaptor.getValue().snapshot().revision()).isEqualTo(target.revision());
        assertThat(targetCaptor.getValue().jobId()).isEqualTo(job.id().value());
        assertThat(targetCaptor.getValue().stage()).isEqualTo("CODEBASE");
        verify(exporter).export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(preparedAnalysis));
        verifyNoInteractions(planner);
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
        when(exporter.export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RepositoryIndexExport(List.of(), mock(com.java.semantic.model.index.SemanticAnalysisEvidence.class)));
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
