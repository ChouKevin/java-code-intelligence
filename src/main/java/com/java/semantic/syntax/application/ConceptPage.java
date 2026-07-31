package com.java.semantic.syntax.application;

import org.springframework.util.Assert;

/** 結構化概念搜尋固定版本結果的一頁計數 */
public record ConceptPage(
        int offset,
        int limit,
        int returnedCount,
        long totalCount,
        boolean hasMore) {

    public ConceptPage {
        Assert.isTrue(offset >= 0, "offset must not be negative");
        Assert.isTrue(limit >= 1 && limit <= 100, "limit must be between 1 and 100");
        Assert.isTrue(returnedCount >= 0 && returnedCount <= limit,
                "returnedCount must be within the requested page limit");
        Assert.isTrue(totalCount >= returnedCount, "totalCount must cover returned candidates");
        Assert.isTrue(hasMore == totalCount > (long) offset + returnedCount,
                "hasMore must match the fixed result window");
    }
}
