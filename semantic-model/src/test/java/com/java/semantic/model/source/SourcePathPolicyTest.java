package com.java.semantic.model.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourcePathPolicyTest {

    @Test
    void admitsUnicodeAndSpacePathsWithoutChangingTheirIdentity() {
        assertEquals("src/付款 flow😀.java", SourcePathPolicy.requireFile("src/付款 flow😀.java"));
        assertEquals("", SourcePathPolicy.requireDirectory(""));
        assertEquals("src/付款 flow", SourcePathPolicy.requireDirectory("src/付款 flow"));
    }

    @Test
    void rejectsTraversalAbsoluteDriveMalformedAndUnrepresentablePaths() {
        List<String> invalidPaths = List.of("", "/src/A.java", "C:src/A.java", "C:/src/A.java",
                "\\\\host\\source", "src\\A.java", "../A.java", "src/../A.java", "src/./A.java",
                "src//A.java", "src/A.java/", "src/\u0000A.java", "src/\nA.java", "src/\uD800.java",
                "src/\uDC00.java");
        for (String invalidPath : invalidPaths) {
            assertThrows(IllegalArgumentException.class, () -> SourcePathPolicy.requireFile(invalidPath), invalidPath);
        }
    }

    @Test
    void enforcesExclusionsAtEveryDepthWithoutExcludingSimilarNames() {
        for (String excludedPath : List.of(".git/config", "src/target/A.java", "build", ".gradle/cache",
                "src/node_modules/module.js", "a/generated/B.java", "a/b/A.class")) {
            assertTrue(SourcePathPolicy.isExcluded(excludedPath), excludedPath);
        }
        for (String visiblePath : List.of("src/targeting/A.java", "src/Build.java", "generated-notes.md",
                "src/.github/workflows/ci.yml", "a/A.class.java")) {
            assertFalse(SourcePathPolicy.isExcluded(visiblePath), visiblePath);
        }
    }
}
