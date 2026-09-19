package com.java.semantic.indexer.analysis;

import com.java.semantic.model.index.SealedGeneration;

/** Decides reuse only when every effective input can be reproduced exactly. */
public interface AnalysisReuseVerifier {
    boolean matches(SealedGeneration candidate, AnalysisTarget target);
}
