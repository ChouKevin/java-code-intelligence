package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.model.codefact.MethodTarget;

import org.springframework.util.Assert;

/**
 * 單一命中證據，由生成成員規則（如 Lombok）或資料存取規則產生
 * <p>
 * declarationTarget 由具備原始碼宣告的規則（如資料存取）填入；純生成成員命中一律為空
 *
 * @param strategy          命中所對應的解析策略
 * @param opaqueSymbol      人類可讀的不透明符號，例如 fqn#getTotal()
 * @param declarationTarget 若命中對應到原始碼宣告則存在，否則為空
 * @param evidence          人類可讀的證據描述，不含原始碼內容
 */
record EvidenceMatch(
        ResolutionStrategy strategy,
        String opaqueSymbol,
        Optional<MethodTarget> declarationTarget,
        List<String> evidence) {

    EvidenceMatch {
        strategy = Objects.requireNonNull(strategy, "strategy is required");
        Assert.hasText(opaqueSymbol, "opaqueSymbol is required");
        declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
    }
}
