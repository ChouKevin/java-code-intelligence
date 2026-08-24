package com.java.semantic.indexer.build;

/** Writer boundary retained by the worker: exporters never know Mongo details. */
@FunctionalInterface
public interface IndexBatchWriter {
    void write(SourceIndexBatch batch);
}
