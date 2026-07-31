package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;

import java.util.Objects;

/** 在固定 revision 以既有 typed identity 讀取 exact content 的查詢 */
public sealed interface ExactContentQuery
        permits ExactContentQuery.MethodSource, ExactContentQuery.MapperStatement, ExactContentQuery.MapperFragment {

    RepositoryId repositoryId();

    RepositoryRevision expectedRevision();

    /** 以完整 canonical 方法目標讀取其宣告原始碼 */
    record MethodSource(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target) implements ExactContentQuery {

        public MethodSource {
            repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 以完整 canonical 方法目標讀取其所有 mapper statement 證據 */
    record MapperStatement(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target) implements ExactContentQuery {

        public MapperStatement {
            repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 以完整 mapper fragment identity 讀取單一 `<sql>` 證據 */
    record MapperFragment(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MapperFragmentIdentity fragmentIdentity) implements ExactContentQuery {

        public MapperFragment {
            repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
        }
    }
}
