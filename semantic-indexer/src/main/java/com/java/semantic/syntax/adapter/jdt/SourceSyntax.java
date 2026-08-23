package com.java.semantic.syntax.adapter.jdt;

import java.util.List;

import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.SourceTypeMetadata;

/**
 * 單一原始檔的抽取結果
 *
 * @param entryPoints 該檔宣告的入口類別
 * @param sourceTypes 該檔宣告的型別 metadata
 */
record SourceSyntax(List<EntryPointClass> entryPoints, List<SourceTypeMetadata> sourceTypes) {
}
