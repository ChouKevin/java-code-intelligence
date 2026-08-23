package com.java.semantic.semantic.domain;

import com.java.semantic.model.codefact.RepositoryRelativeSource;

/** 語意供應者位置經 adapter 安全判定後的來源分類 */
public sealed interface SemanticSourceClassification permits
        SemanticSourceClassification.LocalSource,
        SemanticSourceClassification.OutsideRepository,
        SemanticSourceClassification.UnprovableUri {

    /** 已證實位於目前 repository 的來源 */
    record LocalSource(String sourceFile) implements SemanticSourceClassification {

        public LocalSource {
            sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        }
    }

    /** 已證實不屬於目前 repository */
    enum OutsideRepository implements SemanticSourceClassification {
        INSTANCE
    }

    /** 無法安全證實來源位置 */
    enum UnprovableUri implements SemanticSourceClassification {
        INSTANCE
    }
}
