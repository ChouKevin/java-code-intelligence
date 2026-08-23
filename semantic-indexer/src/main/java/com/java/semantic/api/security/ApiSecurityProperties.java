package com.java.semantic.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** API 權杖設定,未設定時服務一律拒絕流量 */
@ConfigurationProperties(prefix = "semantic.api")
public class ApiSecurityProperties {

    private String apiToken;
    private String adminToken;

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public boolean hasApiToken() {
        return StringUtils.hasText(apiToken);
    }

    /** 以固定時間比較,避免以回應時間逐字元試探 */
    public boolean matchesApiToken(String provided) {
        return matches(apiToken, provided);
    }

    public boolean hasAdminToken() {
        return StringUtils.hasText(apiToken) && StringUtils.hasText(adminToken) && !MessageDigest.isEqual(
                apiToken.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8));
    }

    public boolean matchesAdminToken(String provided) {
        return matches(adminToken, provided);
    }

    private static boolean matches(String expected, String provided) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 不輸出權杖值 */
    @Override
    public String toString() {
        return "ApiSecurityProperties(apiToken=" + (hasApiToken() ? "<set>" : "<unset>")
                + ", adminToken=" + (hasAdminToken() ? "<set>" : "<unset>") + ")";
    }
}
