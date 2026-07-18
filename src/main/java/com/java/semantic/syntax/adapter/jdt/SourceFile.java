package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Path;

/**
 * 一個待解析的 Java 原始檔，連同它所屬的 source root
 *
 * @param path       原始檔的絕對或工作目錄相對路徑
 * @param sourceRoot 該檔所屬的 source root，用來計算 packagePath
 */
record SourceFile(Path path, Path sourceRoot) {

    /** source root 之下的相對路徑，例如 com/example/Foo.java */
    String relativePath() {
        return sourceRoot.relativize(path).toString().replace('\\', '/');
    }
}
