package com.java.semantic.indexer.incremental;

import java.util.Objects;

/** A revision-pinned source-tree change; paths are normalized at this boundary only. */
public record ChangedSource(ChangeKind kind, String oldPath, String newPath, String oldContent, String newContent) {

    public ChangedSource {
        kind = Objects.requireNonNull(kind, "change kind is required");
        oldPath = normalize(oldPath);
        newPath = normalize(newPath);
        oldContent = Objects.requireNonNull(oldContent, "old content is required");
        newContent = Objects.requireNonNull(newContent, "new content is required");
        if ((kind == ChangeKind.ADD && !oldPath.isEmpty()) || (kind == ChangeKind.DELETE && !newPath.isEmpty())
                || (kind == ChangeKind.RENAME && (oldPath.isEmpty() || newPath.isEmpty()))
                || (kind == ChangeKind.MODIFY && (oldPath.isEmpty() || newPath.isEmpty()))) {
            throw new IllegalArgumentException("change paths do not match change kind");
        }
    }

    public static ChangedSource add(String path, String content) {
        return new ChangedSource(ChangeKind.ADD, "", path, "", content);
    }

    public static ChangedSource modify(String path, String oldContent, String newContent) {
        return new ChangedSource(ChangeKind.MODIFY, path, path, oldContent, newContent);
    }

    public static ChangedSource delete(String path, String content) {
        return new ChangedSource(ChangeKind.DELETE, path, "", content, "");
    }

    public static ChangedSource rename(String oldPath, String newPath, String oldContent, String newContent) {
        return new ChangedSource(ChangeKind.RENAME, oldPath, newPath, oldContent, newContent);
    }

    public boolean affectsSupportedSource() {
        return isSupportedPath(oldPath) || isSupportedPath(newPath);
    }

    private static boolean isSupportedPath(String path) {
        return path.endsWith(".java") || path.endsWith(".xml");
    }

    private static String normalize(String path) {
        String requiredPath = Objects.requireNonNull(path, "source path is required").replace('\\', '/');
        if (requiredPath.isEmpty()) {
            return "";
        }
        if (requiredPath.startsWith("/") || requiredPath.contains("//") || requiredPath.startsWith("../")
                || requiredPath.contains("/../") || requiredPath.equals("..")) {
            throw new IllegalArgumentException("source path must be repository relative: " + requiredPath);
        }
        return requiredPath;
    }
}
