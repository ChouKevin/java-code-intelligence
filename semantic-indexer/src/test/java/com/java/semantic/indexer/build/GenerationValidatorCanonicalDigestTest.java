package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.index.ProjectionName;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class GenerationValidatorCanonicalDigestTest {
    @Test
    void produces_the_same_identity_for_permuted_unordered_analysis_evidence() throws Exception {
        Document first = manifest(List.of(project("b", List.of("z", "a")), project("a", List.of("y", "x"))),
                List.of(proof("b", List.of("z", "a")), proof("a", List.of("y", "x"))));
        Document second = manifest(List.of(project("a", List.of("x", "y")), project("b", List.of("a", "z"))),
                List.of(proof("a", List.of("x", "y")), proof("b", List.of("a", "z"))));

        assertThat(digest(first)).isEqualTo(digest(second));
    }

    private static Document manifest(List<Document> projects, List<Document> proofs) {
        return new Document("analysisFingerprint", "a".repeat(64))
                .append("analysisInputs", new Document("projects", projects).append("compilerOptions", new Document("z", "1").append("a", "2")))
                .append("analysisEvidence", new Document("projects", proofs).append("limitations", List.of(
                        new Document("code", "Z"), new Document("code", "A"))));
    }

    private static Document project(String path, List<String> roots) {
        return new Document("projectPath", path).append("roots", roots.stream().map(root -> new Document("path", root)).toList());
    }

    private static Document proof(String path, List<String> roots) {
        return new Document("projectPath", path).append("verifiedSourcePaths", roots);
    }

    @SuppressWarnings("unchecked")
    private static String digest(Document manifest) throws Exception {
        Method method = GenerationValidator.class.getDeclaredMethod("digest", Map.class, Document.class);
        method.setAccessible(true);
        Map<ProjectionName, List<Document>> projections = java.util.Arrays.stream(ProjectionName.values())
                .collect(java.util.stream.Collectors.toMap(value -> value, value -> List.of()));
        return ((com.java.semantic.model.index.ManifestDigest) method.invoke(null, projections, manifest)).value();
    }
}
