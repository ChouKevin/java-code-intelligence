package com.java.semantic.indexer.incremental;

import java.util.List;

/** Reads changes between two exact Git revisions, never from a working tree. */
@FunctionalInterface
public interface GitRevisionDiff {
    List<ChangedSource> diff(String publishedRevision, String selectedRevision);
}
