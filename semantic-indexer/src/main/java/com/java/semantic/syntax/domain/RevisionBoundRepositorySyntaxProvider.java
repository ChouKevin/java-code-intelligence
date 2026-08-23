package com.java.semantic.syntax.domain;

import com.java.semantic.repository.domain.RepositorySnapshot;

/** 以 repository 的精確 revision 提供不可變語法快照的 domain 邊界 */
public interface RevisionBoundRepositorySyntaxProvider {

    /** 取得指定 repository snapshot 的語法快照 */
    RepositorySyntax get(RepositorySnapshot snapshot);
}
