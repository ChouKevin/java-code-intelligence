package com.java.semantic.model.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Exact imported-source membership, not a filename-based repository allowlist. */
public record SourceEvidencePolicy(int version, List<String> includedRoots, Set<String> selectedCodePaths,
        Optional<String> projectGuidePath) {
    public static final int VERSION = 1;

    public SourceEvidencePolicy {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported source policy version");
        }
        includedRoots = List.copyOf(Objects.requireNonNull(includedRoots, "included roots are required"));
        selectedCodePaths = Set.copyOf(Objects.requireNonNull(selectedCodePaths, "selected paths are required"));
        projectGuidePath = Objects.requireNonNull(projectGuidePath, "guide path selection is required");
        includedRoots.forEach(SourceEvidencePolicy::relativePath);
        selectedCodePaths.forEach(SourceEvidencePolicy::relativePath);
        projectGuidePath.ifPresent(path -> {
            relativePath(path);
            if (!path.endsWith(".md")) {
                throw new IllegalArgumentException("project guide must be a Markdown file");
            }
        });
        List<String> roots = includedRoots;
        if (selectedCodePaths.stream().anyMatch(path -> roots.stream()
                .noneMatch(root -> root.equals(".") || path.startsWith(root + "/")))) {
            throw new IllegalArgumentException("selected code must belong to an imported source root");
        }
    }

    public boolean allowsCode(String path) {
        return validPath(path) && selectedCodePaths.contains(path)
                && (path.endsWith(".java") || path.endsWith(".xml"));
    }

    public boolean allowsGuide(String path) {
        return validPath(path) && projectGuidePath.filter(path::equals).isPresent();
    }

    public static boolean allowsChange(Optional<String> beforePath, SourceEvidencePolicy beforePolicy,
            Set<String> availableGuidePathsBefore, Optional<String> afterPath, SourceEvidencePolicy afterPolicy,
            Set<String> availableGuidePathsAfter) {
        return beforePath.map(path -> beforePolicy.allowsCode(path)
                        || beforePolicy.allowsGuide(path) && availableGuidePathsBefore.contains(path)).orElse(true)
                && afterPath.map(path -> afterPolicy.allowsCode(path)
                        || afterPolicy.allowsGuide(path) && availableGuidePathsAfter.contains(path)).orElse(true);
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("source-policy:" + version).getBytes(StandardCharsets.UTF_8));
            for (String root : new TreeSet<>(includedRoots)) {
                frame(digest, (byte) 1, root);
            }
            for (String path : new TreeSet<>(selectedCodePaths)) {
                frame(digest, (byte) 2, path);
            }
            projectGuidePath.ifPresent(path -> frame(digest, (byte) 3, path));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    private static void frame(MessageDigest digest, byte kind, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(kind);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }


    private static void relativePath(String path) {
        if (!validPath(path)) {
            throw new IllegalArgumentException("invalid repository-relative source path");
        }
    }

    public static boolean validPath(String path) {
        if (Objects.isNull(path) || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf('\u0000') >= 0 || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        return path.equals(".") || java.util.Arrays.stream(path.split("/", -1))
                .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
}
