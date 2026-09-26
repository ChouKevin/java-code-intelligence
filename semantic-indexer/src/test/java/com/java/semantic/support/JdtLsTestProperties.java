package com.java.semantic.support;

import com.java.semantic.config.JdtLsProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            currentGroupId(),
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

    public static void prepareSafeCheckoutRoot(Path checkoutRoot) throws IOException {
        Files.setAttribute(checkoutRoot, "unix:gid", Math.toIntExact(LINUX_UID.getAnalysisGid()), LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(checkoutRoot, "unix:mode", 01770, LinkOption.NOFOLLOW_LINKS);
    }

    private static long currentGroupId() {
        try {
            return ((Number) Files.getAttribute(Path.of("."), "unix:gid")).longValue();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
