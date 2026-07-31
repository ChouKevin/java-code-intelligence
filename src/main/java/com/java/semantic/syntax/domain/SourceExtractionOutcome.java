package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 單一 repository 相對來源檔的語法抽取結果 */
public record SourceExtractionOutcome(
        String sourceFile,
        SourceExtractionStatus status,
        Optional<String> reasonCode) {

    public SourceExtractionOutcome {
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        status = Objects.requireNonNull(status, "status is required");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode is required");
        Assert.isTrue(StringUtils.hasText(sourceFile), "sourceFile is required");
        switch (status) {
            case EXTRACTED -> Assert.isTrue(reasonCode.isEmpty(), "extracted reasonCode must be empty");
            case SYNTAX_FAILED -> Assert.isTrue(reasonCode.filter(StringUtils::hasText).isPresent(),
                    "syntax failed reasonCode is required");
        }
    }

    /** 建立已完成抽取的來源檔結果 */
    public static SourceExtractionOutcome extracted(String sourceFile) {
        return new SourceExtractionOutcome(sourceFile, SourceExtractionStatus.EXTRACTED, Optional.empty());
    }

    /** 建立因語法失敗而未抽取的來源檔結果 */
    public static SourceExtractionOutcome syntaxFailed(String sourceFile, String reasonCode) {
        return new SourceExtractionOutcome(sourceFile, SourceExtractionStatus.SYNTAX_FAILED, Optional.of(reasonCode));
    }
}
