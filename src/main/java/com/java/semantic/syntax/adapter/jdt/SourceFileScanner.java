package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * 走訪 source root 底下的 .java 檔
 * <p>
 * 兩條規則與舊分析器刻意不同：
 * 排除測試原始碼時比對的是 repo 相對路徑而非絕對路徑，
 * 且每個檔案都做 toRealPath 收容檢查，symlink 指到 repo 之外時拒讀
 */
@Slf4j
class SourceFileScanner {

    private static final int MAX_DEPTH = 30;

    private static final String JAVA_SUFFIX = ".java";

    private static final String TEST_SOURCE_MARKER = "/src/test/";

    /** 掃描所有 source root，回傳穩定排序後的原始檔清單 */
    List<SourceFile> scan(Path repoRoot, List<Path> sourceRoots) {
        RepositoryContainment containment = RepositoryContainment.of(repoRoot);
        List<SourceFile> files = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            files.addAll(scanRoot(containment, repoRoot, sourceRoot));
        }
        files.sort(Comparator.comparing(file -> file.path().toString()));
        return List.copyOf(files);
    }

    private List<SourceFile> scanRoot(RepositoryContainment containment, Path repoRoot, Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(JAVA_SUFFIX))
                    .filter(path -> isNotTestSource(repoRoot, path))
                    .filter(containment::contains)
                    .map(path -> new SourceFile(path, sourceRoot))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk source root: " + sourceRoot, e);
        }
    }

    /**
     * 測試原始碼排除，比對的是 repo 相對路徑
     * <p>
     * 舊分析器比對絕對路徑，因此 checkout 在 /home/ci/src/test/repo 之下的 repo 會靜默掃出零筆
     */
    private boolean isNotTestSource(Path repoRoot, Path path) {
        String relative = "/" + repoRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        if (relative.contains(TEST_SOURCE_MARKER)) {
            log.debug("Skipping source category={}", "TEST_SOURCE");
            return false;
        }
        return true;
    }
}
