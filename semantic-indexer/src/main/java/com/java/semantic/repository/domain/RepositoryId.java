package com.java.semantic.repository.domain;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 不透明的儲存庫識別字
 *
 * 此值會併入檔案路徑,驗證是唯一的防線
 * 開頭限定 [a-z0-9] 是關鍵:僅檢查結尾片段擋不掉 ../x
 */
public record RepositoryId(String value) {

    private static final Pattern SAFE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    public RepositoryId {
        if (!StringUtils.hasText(value) || !SAFE.matcher(value).matches()) {
            throw new InvalidRepositoryIdException(value);
        }
    }

    public static RepositoryId of(String value) {
        return new RepositoryId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
