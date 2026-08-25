package com.java.semantic.indexer.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Dedicated, fail-closed credential for Indexer mutation endpoints. */
@ConfigurationProperties(prefix = "semantic.indexer")
public class IndexerAdminSecurityProperties {
    private String adminToken;

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public boolean hasAdminToken() {
        return StringUtils.hasText(adminToken);
    }

    public boolean matches(String candidate) {
        if (!StringUtils.hasText(adminToken) || !StringUtils.hasText(candidate)) {
            return false;
        }
        return MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
