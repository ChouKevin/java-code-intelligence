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
        @DefaultValue("/opt/java/openjdk/bin/java") Path javaExecutable,
        @DefaultValue("LINUX_UID") IsolationMode isolationMode,
        @DefaultValue("10001") long analysisUid,
        @DefaultValue("10001") long analysisGid,
        @DefaultValue("/home/analysis") Path analysisHome,
        @DefaultValue("180s") Duration startupTimeout,
        @DefaultValue("900s") Duration importTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("2") int maxActiveWorkspaces,
        @DefaultValue("30m") Duration idleTimeout,
        @DefaultValue("1m") Duration maintenanceInterval,
        @DefaultValue("2g") String maxHeap) {

    public JdtLsProperties {
        Assert.notNull(javaExecutable, "javaExecutable is required");
        Assert.notNull(isolationMode, "isolationMode is required");
        Assert.isTrue(analysisUid >= 0, "analysisUid must not be negative");
        Assert.isTrue(analysisGid >= 0, "analysisGid must not be negative");
        Assert.notNull(analysisHome, "analysisHome is required");
        requirePositive(startupTimeout, "startupTimeout is required and must be positive");
        requirePositive(importTimeout, "importTimeout is required and must be positive");
        requirePositive(requestTimeout, "requestTimeout is required and must be positive");
        requirePositive(idleTimeout, "idleTimeout is required and must be positive");
        requirePositive(maintenanceInterval, "maintenanceInterval is required and must be positive");
        Assert.isTrue(maxActiveWorkspaces > 0, "maxActiveWorkspaces must be positive");
    }

    public JdtLsProperties(
            boolean enabled,
            Path home,
            Path workspaceDataRoot,
            Duration startupTimeout,
            Duration importTimeout,
            Duration requestTimeout,
            int maxActiveWorkspaces,
            Duration idleTimeout,
            Duration maintenanceInterval,
            String maxHeap) {
        this(enabled, home, workspaceDataRoot, Path.of("java"), IsolationMode.LOCAL_TRUSTED, 0, 0,
                Path.of(System.getProperty("user.home", ".")), startupTimeout, importTimeout, requestTimeout,
                maxActiveWorkspaces, idleTimeout, maintenanceInterval, maxHeap);
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

    public Path getJavaExecutable() {
        return javaExecutable;
    }

    public IsolationMode getIsolationMode() {
        return isolationMode;
    }

    public long getAnalysisUid() {
        return analysisUid;
    }

    public long getAnalysisGid() {
        return analysisGid;
    }

    public Path getAnalysisHome() {
        return analysisHome;
    }

    public enum IsolationMode {
        LINUX_UID,
        LOCAL_TRUSTED
    }

    private static void requirePositive(Duration value, String message) {
        Assert.notNull(value, message);
        Assert.isTrue(!value.isZero() && !value.isNegative(), message);
    }
}
