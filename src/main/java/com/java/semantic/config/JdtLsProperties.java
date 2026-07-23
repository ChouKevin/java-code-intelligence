package com.java.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

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
        @DefaultValue("1m") Duration maintenanceInterval,
        @DefaultValue("2g") String maxHeap) {

    public JdtLsProperties {
        requirePositive(startupTimeout, "startupTimeout is required and must be positive");
        requirePositive(importTimeout, "importTimeout is required and must be positive");
        requirePositive(requestTimeout, "requestTimeout is required and must be positive");
        requirePositive(idleTimeout, "idleTimeout is required and must be positive");
        requirePositive(maintenanceInterval, "maintenanceInterval is required and must be positive");
        Assert.isTrue(maxActiveWorkspaces > 0, "maxActiveWorkspaces must be positive");
    }

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

    public Duration getMaintenanceInterval() {
        return maintenanceInterval;
    }

    public String getMaxHeap() {
        return maxHeap;
    }

    private static void requirePositive(Duration value, String message) {
        Assert.notNull(value, message);
        Assert.isTrue(!value.isZero() && !value.isNegative(), message);
    }
}
