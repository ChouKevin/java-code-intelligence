package com.java.semantic.repository.domain;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 已解析的確切版本
 *
 * REMOTE 儲存庫為 40 位小寫 hex 的 commit SHA
 * LOCAL_FIXTURE 沒有 git 歷史,回報字面值 FIXTURE 而非偽造 SHA
 */
public record RepositoryRevision(String value) {

    private static final String FIXTURE_VALUE = "FIXTURE";
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    public RepositoryRevision {
        boolean fixture = FIXTURE_VALUE.equals(value);
        boolean sha = StringUtils.hasText(value) && SHA.matcher(value).matches();
        if (!fixture && !sha) {
            throw new IllegalArgumentException("revision must be a lowercase git sha or FIXTURE");
        }
    }

    public static RepositoryRevision ofSha(String sha) {
        if (!StringUtils.hasText(sha) || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("revision must be a 40-character lowercase git sha: " + sha);
        }
        return new RepositoryRevision(sha);
    }

    public static RepositoryRevision fixture() {
        return new RepositoryRevision(FIXTURE_VALUE);
    }

    @Override
    public String toString() {
        return value;
    }
}
