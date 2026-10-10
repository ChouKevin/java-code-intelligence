package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.support.ModelValidation;
import java.util.List;
import java.util.Objects;

/** Canonical private inputs; the version pins solver policy and approved resource limits. */
public record ResolutionPlan(int formatVersion, SourceContext context, String originFingerprint,
        String sourceManifestDigest, String inventoryDigest, IndexContract.Profile profile,
        RelationContract.Platform platform, List<String> sourceRoots, List<Dependency> dependencies) {
    public ResolutionPlan {
        ModelValidation.require(formatVersion == 1, "unsupported resolution plan");
        context = Objects.requireNonNull(context);
        originFingerprint = ModelValidation.sha256(originFingerprint, "origin fingerprint");
        sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
        inventoryDigest = ModelValidation.sha256(inventoryDigest, "inventory digest");
        ModelValidation.require(IndexContract.relationsProfile().equals(profile), "resolution plan requires relations");
        dependencies = List.copyOf(dependencies);
        RelationContract.Provenance validated = new RelationContract.Provenance("0".repeat(64), platform, sourceRoots,
                dependencies.stream().map(Dependency::artifact).toList());
        platform = validated.platform();
        sourceRoots = validated.sourceRoots();
    }
    public record Dependency(String id, String snapshotFile, String digest, long byteLength, int order) {
        public Dependency {
            RelationContract.Artifact artifact = new RelationContract.Artifact(id, digest, byteLength, order);
            id = artifact.id();
            digest = artifact.digest();
            ModelValidation.require(String.format(java.util.Locale.ROOT, "dependency-%03d.jar", order).equals(snapshotFile),
                    "invalid snapshot name");
        }
        public RelationContract.Artifact artifact() { return new RelationContract.Artifact(id, digest, byteLength, order); }
    }
}
