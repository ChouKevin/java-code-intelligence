package com.java.semantic.syntax.domain;

import java.util.List;

/**
 * 帶有入口方法的類別
 *
 * @param className    巢狀名稱，例如 Outer.Inner
 * @param packageName  套件名稱
 * @param packagePath  source root 之下的檔案相對路徑，例如 com/example/Foo.java
 * @param description  類別 Javadoc
 * @param basePaths    類別層 @RequestMapping 的路徑，沒有 API 入口時為空
 * @param methods      入口方法
 */
public record EntryPointClass(
        String className,
        String packageName,
        String packagePath,
        String description,
        List<String> basePaths,
        List<EntryPointMethod> methods) {

    public EntryPointClass {
        basePaths = List.copyOf(basePaths);
        methods = List.copyOf(methods);
    }
}
