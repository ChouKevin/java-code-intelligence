package com.java.semantic.semantic.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** revision 與 exact target 綁定的完整分析 cache 應用邊界 */
@FunctionalInterface
public interface InternalReferenceAnalysisCache {

    LookupResult lookup(Key key, Supplier<InternalReferenceAnalysis> loader);

    /** 不含分頁參數的唯一 cache key */
    record Key(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            ExactSourceDeclarationTarget target) {

        public Key {
            repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
            revision = Objects.requireNonNull(revision, "revision is required");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** cache lookup 值與不外洩實作型別的觀測結果 */
    record LookupResult(
            InternalReferenceAnalysis value,
            boolean cacheHit,
            boolean cacheStored,
            int entryWeight,
            Duration loadDuration) {

        public LookupResult {
            value = Objects.requireNonNull(value, "value is required");
            loadDuration = Objects.requireNonNull(loadDuration, "loadDuration is required");
            if (entryWeight < 1 || loadDuration.isNegative()) {
                throw new IllegalArgumentException("cache lookup metadata is invalid");
            }
        }
    }
}
