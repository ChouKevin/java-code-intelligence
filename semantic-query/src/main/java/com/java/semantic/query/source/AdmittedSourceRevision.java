package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.nio.file.Path;

/** Owned admission guard; caller closes after the complete reader/process operation. Paths stay private. */
public record AdmittedSourceRevision(SourceContext context, SourceRevisionManifest manifest,
        String manifestDigest, Path tree, Path inventory, SourceReadLocks.Lease lease) implements AutoCloseable {
    public AdmittedSourceRevision {
        java.util.Objects.requireNonNull(lease, "owned read lease");
    }
    @Override public void close() { lease.close(); }
}
