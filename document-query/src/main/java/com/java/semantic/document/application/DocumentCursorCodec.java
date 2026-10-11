package com.java.semantic.document.application;

import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Opaque, process-authenticated continuation positions. */
public final class DocumentCursorCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    record Cursor(String revision, String query, int limit, String path, int sectionStartLine) { }
    private record Payload(String revision, String query, int limit, String path, int sectionStartLine) { }

    private final ObjectMapper mapper;
    private final byte[] key = new byte[32];

    public DocumentCursorCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        new SecureRandom().nextBytes(key);
    }

    String encode(Cursor cursor) {
        validate(cursor.revision(), cursor.query(), cursor.limit(), cursor.path(), cursor.sectionStartLine());
        byte[] payload = mapper.writeValueAsBytes(new Payload(cursor.revision(), cursor.query(), cursor.limit(),
                cursor.path(), cursor.sectionStartLine()));
        String token = ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
        if (token.length() > DocumentContract.MAX_CURSOR_BYTES) {
            throw new IllegalStateException("Generated document cursor exceeded its fixed bound");
        }
        return token;
    }

    Cursor decode(String token) {
        if (Objects.isNull(token) || token.length() > DocumentContract.MAX_CURSOR_BYTES) {
            throw invalid();
        }
        int separator = token.indexOf('.');
        if (separator < 1 || separator != token.lastIndexOf('.') || separator == token.length() - 1) {
            throw invalid();
        }
        String encodedPayload = token.substring(0, separator);
        String encodedSignature = token.substring(separator + 1);
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(encodedPayload);
            signature = DECODER.decode(encodedSignature);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!ENCODER.encodeToString(payload).equals(encodedPayload)
                || !ENCODER.encodeToString(signature).equals(encodedSignature)
                || !MessageDigest.isEqual(signature, sign(payload))) {
            throw invalid();
        }

        Payload decoded;
        try {
            decoded = mapper.readValue(payload, Payload.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalid();
        }
        if (Objects.isNull(decoded)) {
            throw invalid();
        }
        validate(decoded.revision(), decoded.query(), decoded.limit(), decoded.path(), decoded.sectionStartLine());
        return new Cursor(decoded.revision(), decoded.query(), decoded.limit(), decoded.path(), decoded.sectionStartLine());
    }

    private static void validate(String revision, String query, int limit, String path, int sectionStartLine) {
        try {
            RepositoryRevision.ofSha(revision);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
        if (Objects.isNull(query) || query.isBlank()
                || query.codePointCount(0, query.length()) > DocumentContract.MAX_QUERY_CODE_POINTS
                || limit < 1 || limit > DocumentContract.MAX_LIMIT
                || Objects.isNull(path) || path.isEmpty() || path.length() > DocumentContract.MAX_PATH_BYTES
                || path.getBytes(StandardCharsets.UTF_8).length > DocumentContract.MAX_PATH_BYTES
                || sectionStartLine < 1) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static DocumentFailure invalid() {
        return new DocumentFailure(DocumentFailure.Code.INVALID_ARGUMENT);
    }
}
