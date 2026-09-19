package com.java.semantic.indexer.analysis;

import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRepositoryAnalysisPreparationTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String PROCESS_ANNOTATIONS = "org.eclipse.jdt.core.compiler.processAnnotations";

    @Test
    void should_discard_project_local_annotation_processing_options_from_the_shared_syntax_parser() {
        AnalysisInputs inputs = new AnalysisInputs(
                IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                List.of(project("application", "enabled"), project("query", "disabled")));

        Map<String, String> options = DefaultRepositoryAnalysisPreparation.effectiveCompilerOptions(inputs);

        assertThat(options)
                .containsEntry("org.eclipse.jdt.core.compiler.compliance", "21")
                .doesNotContainKey(PROCESS_ANNOTATIONS);
    }

    private static AnalysisInputs.Project project(String path, String processAnnotations) {
        return new AnalysisInputs.Project(
                path,
                DIGEST,
                Map.of(
                        "org.eclipse.jdt.core.compiler.compliance", "21",
                        PROCESS_ANNOTATIONS, processAnnotations),
                List.of(),
                List.of(new AnalysisInputs.Root("src/main/java", "SOURCE", true, List.of())),
                List.of(),
                List.of());
    }
}
