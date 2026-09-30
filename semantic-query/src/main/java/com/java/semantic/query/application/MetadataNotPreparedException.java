package com.java.semantic.query.application;

/** No complete published metadata pair exists for the requested repository/branch. */
public final class MetadataNotPreparedException extends RuntimeException {
    public MetadataNotPreparedException() { super("Git metadata is not prepared"); }
}
