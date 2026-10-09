package com.java.semantic.query.source;

import com.java.semantic.query.application.QueryCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Source continuations are authenticated for one JVM lifetime, never authorization. */
final class SourceReadCursorCodec {
    private static final int MAX_CHARACTERS = 65_536;
    private static final int SIGNATURE_CHARACTERS = 43;
    private static final SecretKeySpec KEY = processKey();

    private SourceReadCursorCodec() { }

    static String encode(String binding, List<String> positions) {
        String payload = QueryCursorCodec.encode(binding, positions);
        if (payload.length() > MAX_CHARACTERS - SIGNATURE_CHARACTERS - 1) {
            throw new IllegalArgumentException("source cursor is too large");
        }
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    static List<String> decode(String cursor, String binding, int expectedPositions) {
        if (cursor.length() > MAX_CHARACTERS) throw new IllegalArgumentException("invalid source cursor");
        int separator = cursor.length() - SIGNATURE_CHARACTERS - 1;
        if (separator <= 0 || cursor.charAt(separator) != '.') {
            throw new IllegalArgumentException("invalid source cursor");
        }
        String payload = cursor.substring(0, separator);
        for (int index = 0; index < payload.length(); index++) {
            char character = payload.charAt(index);
            if (!(character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9' || character == '-' || character == '_')) {
                throw new IllegalArgumentException("invalid source cursor");
            }
        }
        byte[] signature = Base64.getUrlDecoder().decode(cursor.substring(separator + 1));
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw new IllegalArgumentException("invalid source cursor");
        }
        // Authenticate before the existing bounded codec interprets any effective state.
        return QueryCursorCodec.decode(payload, binding, expectedPositions);
    }

    private static SecretKeySpec processKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "HmacSHA256");
    }

    private static byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(KEY);
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 must be available", exception);
        }
    }
}
