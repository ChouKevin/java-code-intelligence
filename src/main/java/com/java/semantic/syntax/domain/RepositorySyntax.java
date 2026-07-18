package com.java.semantic.syntax.domain;

import java.util.List;

/**
 * 一個 repository 的語法層抽取結果
 *
 * @param entryPoints 帶有入口方法的類別
 * @param classes     所有型別的 metadata
 */
public record RepositorySyntax(List<EntryPointClass> entryPoints, List<ClassMetadata> classes) {

    public RepositorySyntax {
        entryPoints = List.copyOf(entryPoints);
        classes = List.copyOf(classes);
    }

    /** 空結果 */
    public static RepositorySyntax empty() {
        return new RepositorySyntax(List.of(), List.of());
    }
}
