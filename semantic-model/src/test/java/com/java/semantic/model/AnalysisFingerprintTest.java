package com.java.semantic.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisFingerprintTest {

    @Test
    void fingerprint_preserves_classpath_content_and_order_but_ignores_compiler_option_insertion_order() {
        AnalysisInputs.Artifact first = new AnalysisInputs.Artifact(0, "maven:g:a:1", "JAR", "a".repeat(64), 10L);
        AnalysisInputs.Artifact second = new AnalysisInputs.Artifact(1, "maven:g:b:1", "JAR", "b".repeat(64), 20L);
        AnalysisInputs.Project ordered = project(options("source", "21", "release", "21"), List.of(first, second));
        AnalysisInputs.Project reversed = project(options("release", "21", "source", "21"), List.of(
                new AnalysisInputs.Artifact(0, second.logicalId(), second.kind(), second.contentDigest(), second.byteLength()),
                new AnalysisInputs.Artifact(1, first.logicalId(), first.kind(), first.contentDigest(), first.byteLength())));
        AnalysisInputs.Project equivalentOptions = project(options("release", "21", "source", "21"), List.of(first, second));

        AnalysisInputs left = inputs(ordered);
        AnalysisInputs right = inputs(reversed);
        AnalysisInputs sameSemantics = inputs(equivalentOptions);

        assertNotEquals(AnalysisFingerprint.from(left).digest(), AnalysisFingerprint.from(right).digest());
        assertEquals(AnalysisFingerprint.from(left).digest(), AnalysisFingerprint.from(sameSemantics).digest());
    }

    private static AnalysisInputs inputs(AnalysisInputs.Project project) {
        return new AnalysisInputs(1, "d".repeat(64), "e".repeat(64), "f".repeat(64), "1".repeat(64), List.of(project));
    }

    private static AnalysisInputs.Project project(Map<String, String> compilerOptions, List<AnalysisInputs.Artifact> classpath) {
        return new AnalysisInputs.Project(".", "c".repeat(64), compilerOptions, List.of(), List.of(), classpath, List.of());
    }

    private static Map<String, String> options(String firstKey, String firstValue, String secondKey, String secondValue) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(firstKey, firstValue);
        options.put(secondKey, secondValue);
        return options;
    }
}
