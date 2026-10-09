package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceInventoryEntry;

/** Extracts syntax from the exact verified bytes of one inventory entry; never resolves dependencies. */
public interface SemanticIndexParser {
    IndexContract.ParsedFile parse(String generation, SourceInventoryEntry entry, byte[] source);
}
