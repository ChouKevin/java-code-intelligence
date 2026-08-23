package com.java.semantic.syntax.domain;

import com.java.semantic.identity.SourceTypeIdentity;

import java.util.List;
import java.util.Objects;

/**
 * 帶有入口方法的類別
 *
 * @param sourceType   原始碼型別身分
 * @param description  類別 Javadoc
 * @param basePaths    類別層 @RequestMapping 的路徑，沒有 API 入口時為空
 * @param methods      入口方法
 */
public record EntryPointClass(
        SourceTypeIdentity sourceType,
        String description,
        List<String> basePaths,
        List<EntryPointMethod> methods) {

    public EntryPointClass {
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        basePaths = List.copyOf(basePaths);
        methods = List.copyOf(methods);
    }
}
