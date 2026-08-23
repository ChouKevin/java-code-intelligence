package com.java.semantic.repository.domain;

import com.java.semantic.model.codefact.RepositoryRelativeSource;

import java.nio.file.Path;
import java.util.Objects;

/** 儲存庫來源檔案的真實路徑收容分類 */
public sealed interface RepositorySourceContainmentResult permits
        RepositorySourceContainmentResult.ContainedSource,
        RepositorySourceContainmentResult.OutsideRepository,
        RepositorySourceContainmentResult.UnprovableSource {

    /** 已證實位於儲存庫內的 regular file */
    record ContainedSource(Path realPath, String sourceFile) implements RepositorySourceContainmentResult {

        public ContainedSource {
            realPath = Objects.requireNonNull(realPath, "realPath is required");
            sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        }
    }

    /** 已證實位於儲存庫外 */
    enum OutsideRepository implements RepositorySourceContainmentResult {
        INSTANCE
    }

    /** 無法安全證實真實 regular file 路徑 */
    enum UnprovableSource implements RepositorySourceContainmentResult {
        INSTANCE
    }
}
