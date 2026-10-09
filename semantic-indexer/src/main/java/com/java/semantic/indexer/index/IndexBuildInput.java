package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.model.support.ModelValidation;
import java.nio.file.Path;
import java.util.Objects;

/** Private filesystem locations for an already-admitted exact source publication. */
public record IndexBuildInput(SourceContext context, String originFingerprint, String generation,
        String sourceManifestDigest, SourceRevisionManifest sourceManifest, Path tree, Path inventory) {
    public IndexBuildInput {
        context = Objects.requireNonNull(context, "source context");
        originFingerprint = ModelValidation.sha256(originFingerprint, "approved origin fingerprint");
        generation = IndexContract.requireGeneration(generation);
        sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
        sourceManifest = Objects.requireNonNull(sourceManifest, "source manifest");
        ModelValidation.require(sourceManifest.context().equals(context), "build source identity mismatch");
        tree = Objects.requireNonNull(tree, "source tree").toAbsolutePath().normalize();
        inventory = Objects.requireNonNull(inventory, "source inventory").toAbsolutePath().normalize();
        ModelValidation.require(tree.getParent().equals(inventory.getParent()), "build source paths must share the sealed revision");
    }
}
