package com.java.semantic.model.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class SourceArtifactDocument {
    private final SourceArtifactId id;
    private final String contentHash;
    private final String utf8Content;
    private final List<Integer> lineOffsets;

    private SourceArtifactDocument(String content) {
        utf8Content = Objects.requireNonNull(content, "content is required");
        contentHash = hash(content);
        id = new SourceArtifactId(contentHash);
        lineOffsets = offsets(content);
    }

    public static SourceArtifactDocument create(String content) {
        return new SourceArtifactDocument(content);
    }

    public SourceArtifactId id() {
        return id;
    }

    public String contentHash() {
        return contentHash;
    }

    public String utf8Content() {
        return utf8Content;
    }

    public List<Integer> lineOffsets() {
        return lineOffsets;
    }

    private static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static List<Integer> offsets(String content) {
        List<Integer> result = new ArrayList<>();
        result.add(0);
        for (int offset = 0; offset < content.length(); offset++) {
            if (content.charAt(offset) == '\r') {
                if (offset + 1 < content.length() && content.charAt(offset + 1) == '\n') {
                    offset++;
                }
                result.add(offset + 1);
            } else if (content.charAt(offset) == '\n') {
                result.add(offset + 1);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceArtifactDocument artifact)) {
            return false;
        }
        return id.equals(artifact.id)
                && contentHash.equals(artifact.contentHash)
                && utf8Content.equals(artifact.utf8Content)
                && lineOffsets.equals(artifact.lineOffsets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, contentHash, utf8Content, lineOffsets);
    }

    @Override
    public String toString() {
        return "SourceArtifactDocument[id=" + id + ", contentHash=" + contentHash + ", utf16Length="
                + utf8Content.length() + ", lineCount=" + lineOffsets.size() + "]";
    }
}
