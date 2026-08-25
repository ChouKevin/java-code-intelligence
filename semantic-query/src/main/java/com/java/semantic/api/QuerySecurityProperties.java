package com.java.semantic.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Dedicated, fail-closed credential for Query HTTP and MCP reads. */
@ConfigurationProperties(prefix = "semantic.query")
public class QuerySecurityProperties {
    private String apiToken;

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public boolean hasApiToken() {
        return StringUtils.hasText(apiToken);
    }

    public boolean matches(String candidate) {
        if (!StringUtils.hasText(apiToken) || !StringUtils.hasText(candidate)) {
            return false;
        }
        return MessageDigest.isEqual(apiToken.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
