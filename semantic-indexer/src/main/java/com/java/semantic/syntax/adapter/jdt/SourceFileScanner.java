package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Path realRepositoryRoot = realPath(repoRoot);
        List<Path> realSourceRoots = sourceRoots.stream()
                .map(this::realPath)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        Map<Path, SourceFile> filesByPath = new LinkedHashMap<>();
        for (Path sourceRoot : realSourceRoots) {
            assertContained(realRepositoryRoot, sourceRoot, "source root");
            for (SourceFile file : scanRoot(realRepositoryRoot, sourceRoot)) {
                filesByPath.putIfAbsent(file.path(), file);
            }
        }
        List<SourceFile> files = new ArrayList<>(filesByPath.values());
        files.sort(Comparator.comparing(file -> file.path().toString()));
        return List.copyOf(files);
    }

    private List<SourceFile> scanRoot(Path repositoryRoot, Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(JAVA_SUFFIX))
                    .map(this::realPath)
                    .filter(path -> isNotTestSource(repositoryRoot, path))
                    .filter(path -> isContained(repositoryRoot, path))
                    .map(path -> new SourceFile(path, sourceRoot, repositoryRoot))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk source root: " + sourceRoot, e);
        }
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve source path: " + path, e);
        }
    }

    private void assertContained(Path repositoryRoot, Path path, String pathKind) {
        if (!isContained(repositoryRoot, path)) {
            throw new IllegalArgumentException(pathKind + " escapes repository root");
        }
    }

    private boolean isContained(Path repositoryRoot, Path path) {
        return path.startsWith(repositoryRoot);
    }

    /**
     * 測試原始碼排除，比對的是 repo 相對路徑
     * <p>
     * 舊分析器比對絕對路徑，因此 checkout 在 /home/ci/src/test/repo 之下的 repo 會靜默掃出零筆
     */
    private boolean isNotTestSource(Path repositoryRoot, Path path) {
        String relative = "/" + repositoryRoot.relativize(path)
                .toString()
                .replace('\\', '/');
        if (relative.contains(TEST_SOURCE_MARKER)) {
            log.debug("Skipping source category={}", "TEST_SOURCE");
            return false;
        }
        return true;
    }
}
