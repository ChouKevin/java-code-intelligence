package com.java.semantic.semantic.application;

/** 單次內部 reference cache lookup 的安全觀測資料 */
public record InternalReferenceCacheMetadata(
        boolean cacheHit,
        boolean cacheStored,
        int entryWeight,
        long loadDurationMs) {

    public InternalReferenceCacheMetadata {
        if (entryWeight < 1 || loadDurationMs < 0) {
            throw new IllegalArgumentException("cache metadata is invalid");
        }
    }
}
