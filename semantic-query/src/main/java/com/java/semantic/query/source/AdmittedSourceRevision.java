package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.nio.file.Path;

/** Paths are private admission evidence, never part of a public result. */
public record AdmittedSourceRevision(SourceContext context, SourceRevisionManifest manifest,
        String manifestDigest, Path tree, Path inventory) { }
