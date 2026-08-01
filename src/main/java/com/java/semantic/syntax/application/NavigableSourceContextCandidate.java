package com.java.semantic.syntax.application;

import java.util.Objects;

/**
 * 將 resolver 產生的 context evidence 與 application 決定的 retry 查詢分離
 */
public record NavigableSourceContextCandidate(
        SourceContextCandidate candidate,
        DiscoveryFollowUp retry) {

    public NavigableSourceContextCandidate {
        candidate = Objects.requireNonNull(candidate, "candidate");
        retry = Objects.requireNonNull(retry, "retry");
    }
}
