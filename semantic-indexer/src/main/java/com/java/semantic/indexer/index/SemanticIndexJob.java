package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationJob;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Accepted intent never changes when private execution state advances. */
public record SemanticIndexJob(int formatVersion, String jobId, String repositoryId, String requestId,
        String revision, String originFingerprint, String sourceManifestDigest, String inventoryDigest,
        String indexGeneration, Optional<String> expectedSelection, Phase phase, Instant acceptedAt,
        Optional<Receipt> publication, Optional<String> failureCode,
        Optional<IndexAnalysisIntent> acceptedAnalysis) implements PreparationJob {
    public static final int FORMAT_VERSION = 2;
    public enum Phase { ACCEPTED, RUNNING, COMPLETE, FAILED }

    public SemanticIndexJob {
        ModelValidation.require(formatVersion == 1 || formatVersion == FORMAT_VERSION, "unsupported private index job format");
        acceptedAnalysis = Objects.isNull(acceptedAnalysis) ? Optional.empty() : acceptedAnalysis;
        ModelValidation.require((formatVersion == FORMAT_VERSION) == acceptedAnalysis.isPresent(), "invalid accepted analysis format");
        jobId = new IndexJobId(jobId).value();
        repositoryId = new RepositoryId(repositoryId).value();
        requestId = new PreparationRequestId(requestId).value();
        revision = RepositoryRevision.ofSha(revision).value();
        originFingerprint = ModelValidation.sha256(originFingerprint, "origin fingerprint");
        sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
        inventoryDigest = ModelValidation.sha256(inventoryDigest, "inventory digest");
        indexGeneration = IndexContract.requireGeneration(indexGeneration);
        expectedSelection = Objects.requireNonNull(expectedSelection);
        expectedSelection.ifPresent(IndexContract::requireGeneration);
        phase = Objects.requireNonNull(phase);
        acceptedAt = Objects.requireNonNull(acceptedAt);
        publication = Objects.requireNonNull(publication);
        failureCode = Objects.requireNonNull(failureCode);
        failureCode.ifPresent(code -> ModelValidation.require(code.matches("[A-Z][A-Z0-9_]{0,63}"), "invalid failure code"));
        ModelValidation.require((phase == Phase.COMPLETE) == publication.isPresent()
                && (phase == Phase.FAILED) == failureCode.isPresent(), "invalid semantic job terminal state");
        if (publication.isPresent()) {
            Receipt receipt = publication.orElseThrow();
            ModelValidation.require(receipt.jobId().equals(jobId) && receipt.requestId().equals(requestId)
                    && receipt.manifest().context().repositoryId().equals(repositoryId)
                    && receipt.manifest().context().revision().equals(revision)
                    && receipt.manifest().indexGeneration().equals(indexGeneration)
                    && receipt.manifest().sourceManifestDigest().equals(sourceManifestDigest)
                    && receipt.manifest().inventoryDigest().equals(inventoryDigest)
                    && receipt.manifest().originFingerprint().equals(originFingerprint), "publication intent mismatch");
            if (acceptedAnalysis.isPresent()) {
                IndexAnalysisIntent intent = acceptedAnalysis.orElseThrow();
                ModelValidation.require(receipt.manifest().profile().equals(intent.profile())
                        && receipt.manifest().analysisPlanDigest().equals(intent.analysisPlanDigest()), "publication analysis mismatch");
            }
        }
    }

    /** Legacy queued work never acquires new Relations settings. */
    public IndexAnalysisIntent analysis() { return acceptedAnalysis.orElseGet(IndexAnalysisIntent::syntax); }
    public SemanticIndexJob withPhase(Phase next) { return changed(next, Optional.empty(), Optional.empty()); }
    public SemanticIndexJob completed(Receipt receipt) { return changed(Phase.COMPLETE, Optional.of(receipt), Optional.empty()); }
    public SemanticIndexJob failed(String code) { return changed(Phase.FAILED, Optional.empty(), Optional.of(code)); }
    private SemanticIndexJob changed(Phase next, Optional<Receipt> receipt, Optional<String> code) {
        return new SemanticIndexJob(formatVersion, jobId, repositoryId, requestId, revision, originFingerprint,
                sourceManifestDigest, inventoryDigest, indexGeneration, expectedSelection, next, acceptedAt, receipt, code, acceptedAnalysis);
    }
}
