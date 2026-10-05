package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract;

public interface SourceRevisionCatalog {
    SourceReadContract.RepositoryCollection listRepositories(SourceReadContract.RepositoryRequest request);
    SourceReadContract.ContextResult getContext(SourceReadContract.ContextRequest request);
    /** Transfers ownership; close after the complete source operation, including child-process cleanup. */
    AdmittedSourceRevision admit(SourceContext context);
}
