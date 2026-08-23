package com.java.semantic.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Creates a fixed-length opaque diagnostic identifier for a complete method target. */
public final class MethodTargetDiagnosticId {

    private MethodTargetDiagnosticId() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String from(MethodTarget target) {
        Objects.requireNonNull(target, "target is required");
        MessageDigest digest = sha256();
        update(digest, target.sourceType().sourceFile());
        update(digest, target.sourceType().javaType().packageName());
        update(digest, target.sourceType().javaType().className());
        update(digest, target.methodName());
        List<String> parameterTypes = target.parameterTypes();
        updateInteger(digest, parameterTypes.size());
        for (String parameterType : parameterTypes) {
            update(digest, parameterType);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        updateInteger(digest, valueBytes.length);
        digest.update(valueBytes);
    }

    private static void updateInteger(MessageDigest digest, int value) {
        byte[] encodedValue = ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
        digest.update(encodedValue);
    }
}
