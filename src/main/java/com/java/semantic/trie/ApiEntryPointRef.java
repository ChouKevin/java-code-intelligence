package com.java.semantic.trie;

/** 單一儲存庫 API handler 的 trie 內部資料 */
public record ApiEntryPointRef(
        String repoId,
        String packageName,
        String className,
        String methodName,
        String httpMethod,
        String routeTemplate) {
}
