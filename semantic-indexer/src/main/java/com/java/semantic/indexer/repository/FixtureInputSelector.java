package com.java.semantic.indexer.repository;

import java.nio.file.Path;
import java.util.Set;

/** Shared planner input selector for local fixtures. */
public final class FixtureInputSelector {
    private static final Set<String> SOURCE_ROOTS = Set.of("java", "kotlin", "groovy", "resources");
    private static final Set<String> BUILD_OUTPUT_DIRECTORIES = Set.of("target", "build", "out", "generated");

    public boolean excludes(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        for (Path segment : relative) {
            String name = segment.toString();
            if (".git".equals(name) || ".gradle".equals(name)) {
                return true;
            }
            if (!isSourceTree(relative) && BUILD_OUTPUT_DIRECTORIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean includes(Path relative) {
        return relative.getNameCount() > 0 && !excludes(relative);
    }

    private static boolean isSourceTree(Path relative) {
        for (int index = 0; index <= relative.getNameCount() - 3; index++) {
            if ("src".equals(relative.getName(index).toString())
                    && ("main".equals(relative.getName(index + 1).toString())
                    || "test".equals(relative.getName(index + 1).toString()))
                    && SOURCE_ROOTS.contains(relative.getName(index + 2).toString())) {
                return true;
            }
        }
        return false;
    }
}
