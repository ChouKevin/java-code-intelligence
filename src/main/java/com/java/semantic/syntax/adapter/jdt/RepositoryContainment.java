package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

/**
 * repo 收容檢查
 * <p>
 * normalize 是純字面運算，攔不住解析到 repo 之外的 symlink，因此一律以 toRealPath 比對
 * 原始碼與 mapper XML 是兩條會開檔的走訪路徑，共用這一份判準，避免只有其中一條擋得住
 */
@Slf4j
record RepositoryContainment(Path realRepositoryRoot) {

    /** 以 repo 根目錄建立檢查器，根目錄本身先解析成真實路徑 */
    static RepositoryContainment of(Path repositoryRoot) {
        return new RepositoryContainment(toRealPath(repositoryRoot));
    }

    /** 檔案解析後是否仍落在 repo 之內，不在時記錄並拒讀 */
    boolean contains(Path path) {
        Path realPath = toRealPath(path);
        if (realPath.startsWith(realRepositoryRoot)) {
            return true;
        }
        log.warn("Refusing to read a file that resolves outside the repository: {} -> {}", path, realPath);
        return false;
    }

    private static Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve real path: " + path, e);
        }
    }
}
