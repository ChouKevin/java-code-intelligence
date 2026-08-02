package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一個待解析的 Java 原始檔，連同它所屬的 source root
 *
 * @param path       原始檔的 real path
 * @param sourceRoot 該檔所屬的 source root，用來計算來源根目錄相對路徑
 * @param repositoryRoot repository 的 real path
 */
record SourceFile(Path path, Path sourceRoot, Path repositoryRoot) {

    SourceFile {
        path = Objects.requireNonNull(path, "path is required").normalize();
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot is required").normalize();
        repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot is required").normalize();
    }

    /** source root 之下的相對路徑，例如 com/example/Foo.java */
    String sourceRootRelativePath() {
        return normalizedRelativePath(sourceRoot);
    }

    /** repository 之下的相對路徑，例如 module-a/src/main/java/com/example/Foo.java */
    String repositoryRelativePath() {
        return normalizedRelativePath(repositoryRoot);
    }

    /** 提供需要來源根目錄相對路徑的語法解析 consumers */
    String relativePath() {
        return sourceRootRelativePath();
    }

    private String normalizedRelativePath(Path root) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
