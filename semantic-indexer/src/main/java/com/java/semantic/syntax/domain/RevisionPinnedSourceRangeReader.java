package com.java.semantic.syntax.domain;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceRangeSegment;

import com.java.semantic.repository.domain.RepositorySnapshot;

/** 在 repository 讀鎖已固定的 snapshot 內讀取 bounded canonical 來源位置 */
public interface RevisionPinnedSourceRangeReader {

    /** 讀取位置，呼叫端必須持有由 RepositoryApplicationService.withSnapshot 提供的 snapshot */
    SourceRangeSegment read(
            RepositorySnapshot snapshot,
            SourceRange location,
            int contextLines,
            SourceRange authority);
}
