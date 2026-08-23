package com.java.semantic.model.codefact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class MethodTargetDiagnosticId {

    private MethodTargetDiagnosticId() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String from(MethodTarget target) {
        MethodTarget methodTarget = Objects.requireNonNull(target, "method target is required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(
                    methodTarget.canonicalForm().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
