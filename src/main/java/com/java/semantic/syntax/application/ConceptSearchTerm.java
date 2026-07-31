package com.java.semantic.syntax.application;

import java.util.Locale;
import java.util.Objects;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 由用戶端輸入正規化後的單一概念搜尋條件 */
public record ConceptSearchTerm(String value, ConceptMatchMode matchMode) {

    private static final int MINIMUM_LENGTH = 2;

    private static final int MAXIMUM_LENGTH = 128;

    public ConceptSearchTerm {
        value = Objects.requireNonNull(value, "value is required");
        matchMode = Objects.requireNonNull(matchMode, "matchMode is required");
        Assert.isTrue(value.equals(value.strip()), "term must not contain surrounding whitespace");
        Assert.isTrue(StringUtils.hasText(value), "term is required");
        Assert.isTrue(value.codePoints().noneMatch(Character::isISOControl), "term must not contain control characters");
        if (value.contains("*")) {
            Assert.isTrue(value.endsWith("*") && value.indexOf('*') == value.length() - 1,
                    "only one trailing prefix marker is supported");
            value = value.substring(0, value.length() - 1);
            matchMode = ConceptMatchMode.TOKEN_PREFIX;
        }
        Assert.isTrue(value.length() >= MINIMUM_LENGTH && value.length() <= MAXIMUM_LENGTH,
                "term length must be between 2 and 128 characters");
        value = value.toLowerCase(Locale.ROOT);
    }
}
