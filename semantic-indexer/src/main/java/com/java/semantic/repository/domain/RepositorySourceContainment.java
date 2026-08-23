package com.java.semantic.repository.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 以真實路徑判定 regular source file 是否受儲存庫收容 */
public final class RepositorySourceContainment {

    /** 分類候選檔案且只對安全收容的 regular file 揭露相對路徑 */
    public RepositorySourceContainmentResult classify(Path repositoryRoot, Path candidate) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot is required");
        Objects.requireNonNull(candidate, "candidate is required");
        try {
            Path realRoot = repositoryRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!Files.isRegularFile(realCandidate)) {
                return RepositorySourceContainmentResult.UnprovableSource.INSTANCE;
            }
            if (!realCandidate.startsWith(realRoot)) {
                return RepositorySourceContainmentResult.OutsideRepository.INSTANCE;
            }
            String sourceFile = realRoot.relativize(realCandidate).toString().replace('\\', '/');
            return new RepositorySourceContainmentResult.ContainedSource(realCandidate, sourceFile);
        } catch (IOException | SecurityException exception) {
            return RepositorySourceContainmentResult.UnprovableSource.INSTANCE;
        }
    }
}
