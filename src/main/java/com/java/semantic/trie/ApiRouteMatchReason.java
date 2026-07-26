package com.java.semantic.trie;

/** 描述 API 路由候選與查詢之間可驗證的結構關係 */
public enum ApiRouteMatchReason {
    EXACT_NORMALIZED_PATH,
    TEMPLATE_MATCH,
    HTTP_METHOD_MATCH,
    SHARED_STATIC_SEGMENT,
    POSITIONAL_STATIC_SEGMENT,
    SAME_SEGMENT_COUNT
}
