package com.java.semantic.syntax.application;

import java.util.Set;

import com.java.semantic.syntax.domain.RepositorySyntax;

/** 從既有 RepositorySyntax metadata 投影一組受限概念種類的 provider */
public interface ConceptProvider {

    /** provider 的穩定識別，重複註冊屬應用程式契約錯誤 */
    String providerId();

    /** 此 provider 可投影的 closed concept kinds */
    Set<ConceptKind> supportedKinds();

    /** 只讀取已抽取 metadata，絕不掃描 source body 或檔案 */
    ConceptProviderProjection project(RepositorySyntax syntax);
}
