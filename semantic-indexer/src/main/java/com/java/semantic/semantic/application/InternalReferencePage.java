package com.java.semantic.semantic.application;

/** 完整排序 group 清單投影出的單頁 metadata */
public record InternalReferencePage(
        int offset,
        int limit,
        int returnedCount,
        int totalCount,
        boolean hasMore) {

    public InternalReferencePage {
        if (offset < 0 || limit < 1 || returnedCount < 0 || returnedCount > limit
                || totalCount < returnedCount) {
            throw new IllegalArgumentException("internal reference page is invalid");
        }
        if (hasMore != ((long) offset + returnedCount < totalCount)) {
            throw new IllegalArgumentException("hasMore must match the page continuation");
        }
    }
}
