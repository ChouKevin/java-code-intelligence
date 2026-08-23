package com.java.semantic.semantic.domain;

import com.java.semantic.identity.RepositoryRelativeSource;

import java.util.Objects;

/** JDT LS reference 位置經 adapter 安全分類後的不可變結果 */
public sealed interface SemanticReferenceLocation permits
        SemanticReferenceLocation.LocalSource,
        SemanticReferenceLocation.OutsideRepository,
        SemanticReferenceLocation.UnprovableUri {

    /** 已證實位於目前 repository 的 reference 位置 */
    record LocalSource(String sourceFile, SemanticRange range) implements SemanticReferenceLocation {

        public LocalSource {
            sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
            Objects.requireNonNull(range, "range is required");
        }
    }

    /** 已證實不屬於目前 repository 的 reference 位置 */
    enum OutsideRepository implements SemanticReferenceLocation {
        INSTANCE
    }

    /** 無法安全證實來源位置的 reference */
    enum UnprovableUri implements SemanticReferenceLocation {
        INSTANCE
    }
}
