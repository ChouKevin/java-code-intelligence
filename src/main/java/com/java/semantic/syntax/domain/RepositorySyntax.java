package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一個 repository 的語法層抽取結果
 *
 * @param entryPoints 帶有入口方法的類別
 * @param classes     所有型別的 metadata
 * @param extractionOutcomes 每個來源檔的抽取覆蓋結果
 * @param mapperEvidenceIndex mapper 原始證據索引，未建立時保持空值以維持既有快照契約
 */
public record RepositorySyntax(
        List<EntryPointClass> entryPoints,
        List<ClassMetadata> classes,
        List<SourceExtractionOutcome> extractionOutcomes,
        Optional<MapperEvidenceIndex> mapperEvidenceIndex) {

    public RepositorySyntax {
        entryPoints = List.copyOf(entryPoints);
        classes = List.copyOf(classes);
        extractionOutcomes = List.copyOf(extractionOutcomes);
        mapperEvidenceIndex = Objects.requireNonNull(mapperEvidenceIndex, "mapperEvidenceIndex is required");
    }

    /** 保留既有 callers 的三個集合建構契約 */
    public RepositorySyntax(
            List<EntryPointClass> entryPoints,
            List<ClassMetadata> classes,
            List<SourceExtractionOutcome> extractionOutcomes) {
        this(entryPoints, classes, extractionOutcomes, Optional.empty());
    }

    /** 保留既有 callers 的兩個集合建構契約 */
    public RepositorySyntax(List<EntryPointClass> entryPoints, List<ClassMetadata> classes) {
        this(entryPoints, classes, List.of(), Optional.empty());
    }

    /** 空結果 */
    public static RepositorySyntax empty() {
        return new RepositorySyntax(List.of(), List.of());
    }
}
