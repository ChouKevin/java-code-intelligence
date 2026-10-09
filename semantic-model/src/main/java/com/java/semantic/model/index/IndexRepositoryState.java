package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One atomically replaced index authority, independent of source current/publication bytes. */
public record IndexRepositoryState(int formatVersion, String repositoryId, String originFingerprint,
        Map<String, RevisionIndexes> revisions) {
    public IndexRepositoryState {
        ModelValidation.require(formatVersion > 0, "index state format must be positive");
        repositoryId = new RepositoryId(repositoryId).value();
        originFingerprint = ModelValidation.sha256(originFingerprint, "approved origin fingerprint");
        revisions = Map.copyOf(Objects.requireNonNull(revisions, "revision indexes"));
        for (Map.Entry<String, RevisionIndexes> revision : revisions.entrySet()) {
            RepositoryRevision.ofSha(revision.getKey());
            for (Receipt receipt : revision.getValue().generations().values()) {
                IndexManifest manifest = receipt.manifest();
                ModelValidation.require(manifest.context().repositoryId().equals(repositoryId)
                        && manifest.context().revision().equals(revision.getKey())
                        && manifest.originFingerprint().equals(originFingerprint), "index membership identity mismatch");
            }
        }
    }

    public record RevisionIndexes(String sourceManifestDigest, Optional<String> selectedGeneration,
            Map<String, Receipt> generations) {
        public RevisionIndexes {
            sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
            selectedGeneration = Objects.requireNonNull(selectedGeneration, "selected generation");
            selectedGeneration.ifPresent(IndexContract::requireGeneration);
            generations = Map.copyOf(Objects.requireNonNull(generations, "published generations"));
            if (selectedGeneration.isPresent()) {
                ModelValidation.require(generations.containsKey(selectedGeneration.orElseThrow()), "selection must have published membership");
            }
            for (Map.Entry<String, Receipt> generation : generations.entrySet()) {
                IndexContract.requireGeneration(generation.getKey());
                ModelValidation.require(generation.getKey().equals(generation.getValue().manifest().indexGeneration())
                        && sourceManifestDigest.equals(generation.getValue().manifest().sourceManifestDigest()),
                        "generation/source manifest membership mismatch");
            }
        }
    }

    public record Receipt(IndexManifest manifest, String manifestDigest, String jobId, String requestId) {
        public Receipt {
            manifest = Objects.requireNonNull(manifest, "index manifest");
            manifestDigest = ModelValidation.sha256(manifestDigest, "index manifest digest");
            jobId = IndexContract.requireGeneration(jobId);
            requestId = IndexContract.requireGeneration(requestId);
        }
    }
}
