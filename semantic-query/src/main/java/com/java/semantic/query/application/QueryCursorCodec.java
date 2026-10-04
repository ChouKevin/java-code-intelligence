package com.java.semantic.query.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Continuations carry ordering positions, never authorization. Readers validate each decoded position. */
public final class QueryCursorCodec {
    private static final int VERSION = 1;
    private static final int MAX_CHARACTERS = 65536;
    private static final int MAX_POSITIONS = 32;
    private static final String SCHEMA = "source-first-v1";
    private QueryCursorCodec() { }

    public static String binding(String operation, List<String> normalizedFields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            frame(digest, Integer.toString(VERSION));
            frame(digest, SCHEMA);
            frame(digest, operation);
            for (String field : normalizedFields) frame(digest, field);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }


    public static String encode(String binding, List<String> positions) {
        if (positions.size() > MAX_POSITIONS) throw new IllegalArgumentException("cursor has too many positions");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                text(output, binding);
                output.writeInt(positions.size());
                for (String position : positions) text(output, position);
            }
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (encoded.length() > MAX_CHARACTERS) throw new IllegalArgumentException("cursor is too large");
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static List<String> decode(String cursor, String binding, int expectedPositions) {
        try {
            if (cursor.length() > MAX_CHARACTERS || expectedPositions < 0 || expectedPositions > MAX_POSITIONS) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(cursor)))) {
                if (input.readInt() != VERSION || !text(input).equals(binding) || input.readInt() != expectedPositions) {
                    throw new IllegalArgumentException("cursor does not match this request");
                }
                List<String> positions = new ArrayList<>(expectedPositions);
                for (int index = 0; index < expectedPositions; index++) positions.add(text(input));
                if (input.available() != 0) throw new IllegalArgumentException("cursor is invalid");
                return List.copyOf(positions);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid for this request", exception);
        }
    }

    // Frame UTF16 code units, not replacement-encoded UTF8: distinct literal inputs stay distinct.
    private static void frame(MessageDigest digest, String value) {
        int length = value.length();
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        output.writeInt(value.length());
        output.writeChars(value);
    }
    private static String text(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available() / 2) throw new IllegalArgumentException("cursor is invalid");
        char[] value = new char[length];
        for (int index = 0; index < length; index++) value[index] = input.readChar();
        return new String(value);
    }
}
