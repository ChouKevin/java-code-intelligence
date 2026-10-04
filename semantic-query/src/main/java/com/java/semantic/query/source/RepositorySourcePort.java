package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceReadContract;

public interface RepositorySourcePort {
    SourceReadContract.FileCollection listFiles(AdmittedSourceRevision admitted, SourceReadContract.FileListRequest request);
    SourceReadContract.TextSearchResult searchText(AdmittedSourceRevision admitted, SourceReadContract.TextSearchRequest request);
    SourceReadContract.SourceResult readSource(AdmittedSourceRevision admitted, SourceReadContract.ReadSourceRequest request);
}
