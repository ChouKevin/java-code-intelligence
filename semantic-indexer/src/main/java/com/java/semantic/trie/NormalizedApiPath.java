package com.java.semantic.trie;

/** 正規化後的路由路徑與已驗證 HTTP 方法 */
public record NormalizedApiPath(String path, String httpMethod) {
}
