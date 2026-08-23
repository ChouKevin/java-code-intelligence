package com.java.semantic.indexer.repository;

import java.nio.file.Path;

/** Shared planner input selector for local fixtures. */
public final class FixtureInputSelector {
    public boolean excludes(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        for (Path segment : relative) {
            String name = segment.toString();
            if (".git".equals(name) || "target".equals(name) || "build".equals(name)
                    || ".gradle".equals(name) || "out".equals(name) || "generated".equals(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean includes(Path relative) {
        return relative.getNameCount() > 0 && !excludes(relative);
    }
}
