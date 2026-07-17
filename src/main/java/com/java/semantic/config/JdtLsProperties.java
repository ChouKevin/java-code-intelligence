package com.java.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/** 定義 JDT Language Server 程序與工作區生命週期設定 */
@ConfigurationProperties(prefix = "semantic.jdtls")
public record JdtLsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("/opt/jdtls") Path home,
        @DefaultValue("/data/jdtls") Path workspaceDataRoot,
        @DefaultValue("180s") Duration startupTimeout,
        @DefaultValue("900s") Duration importTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("2") int maxActiveWorkspaces,
        @DefaultValue("30m") Duration idleTimeout,
        @DefaultValue("2g") String maxHeap) {

    public boolean isEnabled() {
        return enabled;
    }

    public Path getHome() {
        return home;
    }

    public Path getWorkspaceDataRoot() {
        return workspaceDataRoot;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public Duration getImportTimeout() {
        return importTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public int getMaxActiveWorkspaces() {
        return maxActiveWorkspaces;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public String getMaxHeap() {
        return maxHeap;
    }
}
