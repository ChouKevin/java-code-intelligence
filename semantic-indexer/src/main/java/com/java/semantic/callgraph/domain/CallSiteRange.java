package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

/**
 * 公開呼叫點的完整半開區間。
 *
 * <p>sourceFile 為服務端已解析的工作區相對來源檔路徑。行與字元座標皆為零基；
 * 起點包含、終點不包含，故同一行相鄰呼叫的終點可等於下一個呼叫的起點。此規約將
 * LSP 零基座標轉換為公開 API 座標，避免呼叫端混用兩種座標系。</p>
 */
public record CallSiteRange(
        String sourceFile,
        int startLine,
        int startCharacter,
        int endLine,
        int endCharacter) {

    public CallSiteRange {
        Assert.hasText(sourceFile, "sourceFile is required");
        Assert.isTrue(startLine >= 0, "startLine must not be negative");
        Assert.isTrue(startCharacter >= 0, "startCharacter must not be negative");
        Assert.isTrue(endLine >= 0, "endLine must not be negative");
        Assert.isTrue(endCharacter >= 0, "endCharacter must not be negative");
        Assert.isTrue(endLine > startLine || (endLine == startLine && endCharacter > startCharacter),
                "end must be after start");
    }
}
