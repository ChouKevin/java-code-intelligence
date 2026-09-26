package com.java.semantic.support;

import com.java.semantic.config.JdtLsProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Shared LINUX_UID policy for tests that exercise managed Git ownership checks. */
public final class JdtLsTestProperties {
    private static final JdtLsProperties LINUX_UID = new JdtLsProperties(
            false,
            Path.of("/opt/jdtls"),
            Path.of("/data/jdtls"),
            Path.of("/opt/java/openjdk/bin/java"),
            JdtLsProperties.IsolationMode.LINUX_UID,
            10001,
            10001,
            Path.of("/home/analysis"),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            1,
            Duration.ofMinutes(1),
            Duration.ofMinutes(1),
            "768m");

    private JdtLsTestProperties() {
    }

    public static JdtLsProperties linuxUid() {
        return LINUX_UID;
    }
}
