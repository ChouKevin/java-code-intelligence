package com.java.semantic.syntax.domain;

import java.nio.file.Path;

/**
 * 語法層抽取的對外契約
 * <p>
 * 這一層只讀形狀：路由、監聽、排程、型別 metadata 與 MyBatis SQL
 * 呼叫目標、receiver 與型別解析仍然只屬於 JDT LS
 */
public interface SyntaxExtractionService {

    /**
     * 抽取指定 repository 的語法資訊
     *
     * @param repositoryRoot repository 的工作目錄根
     * @return 抽取結果，找不到任何 source root 時為空結果
     * @throws SyntaxExtractionException 掃描或解析過程失敗
     */
    RepositorySyntax extract(Path repositoryRoot);
}
