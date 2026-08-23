package com.java.semantic.model.codefact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record CodeFactId(String value) {

    public CodeFactId {
        Objects.requireNonNull(value, "code fact id is required");
        if (!value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("code fact id must be a lowercase SHA-256 hash");
        }
    }

    public static CodeFactId from(CodeFactIdentity identity) {
        CodeFactIdentity requiredIdentity = Objects.requireNonNull(identity, "code fact identity is required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new CodeFactId(HexFormat.of().formatHex(digest.digest(
                    requiredIdentity.canonicalForm().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
