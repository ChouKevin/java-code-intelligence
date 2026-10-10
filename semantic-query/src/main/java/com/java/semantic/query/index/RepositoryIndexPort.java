package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.query.source.AdmittedSourceRevision;
import java.util.Optional;

/** Borrows the source admission lease; closes its own SQL resources before returning. */
public interface RepositoryIndexPort {
    IndexContract.Discovery discover(AdmittedSourceRevision source);
    IndexContract.FoldResult folds(AdmittedSourceRevision source, String path, Optional<String> generation);
    IndexContract.Result<IndexContract.Declaration> searchCode(AdmittedSourceRevision source, IndexContract.SearchCodeRequest request);
    IndexContract.Result<IndexContract.Declaration> outline(AdmittedSourceRevision source, IndexContract.OutlineRequest request);
    IndexContract.Result<IndexContract.EntryCandidate> entryPoints(AdmittedSourceRevision source, IndexContract.EntryPointsRequest request);
    RelationContract.Result findRelations(AdmittedSourceRevision source, RelationContract.Request request);
}
