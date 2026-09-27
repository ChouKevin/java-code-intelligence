package com.java.semantic.indexer.analysis;

import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(inputs.projects()).extracting(AnalysisInputs.Project::compilerOptions)
                .containsExactly(
                        Map.of("org.eclipse.jdt.core.compiler.compliance", "21", PROCESS_ANNOTATIONS, "enabled"),
                        Map.of("org.eclipse.jdt.core.compiler.compliance", "21", PROCESS_ANNOTATIONS, "disabled"));
        AnalysisInputs changed = new AnalysisInputs(
                IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                List.of(project("application", "disabled"), project("query", "disabled")));
        assertThat(AnalysisFingerprint.from(inputs))
                .isNotEqualTo(AnalysisFingerprint.from(changed));
    }

    @Test
    void should_plan_safe_imported_production_roots_even_when_resources_have_no_java(@TempDir Path repository) throws Exception {
        Path productionRoot = Files.createDirectories(repository.resolve("module-a/src/main/java"));
        Path resourceRoot = Files.createDirectories(repository.resolve("module-a/src/main/resources/mapper"));
        Path testRoot = Files.createDirectories(repository.resolve("module-a/src/test/java"));
        Files.writeString(productionRoot.resolve("Service.java"), "class Service {}");
        Files.writeString(resourceRoot.resolve("ServiceMapper.xml"),
                "<mapper namespace=\"ServiceMapper\"><select id=\"find\">select 1</select></mapper>");
        Files.writeString(testRoot.resolve("TestService.java"), "class TestService {}");
        RepositorySnapshot snapshot = new RepositorySnapshot(
                RepositoryId.of("actual-review"),
                repository,
                RepositoryRevision.ofSha("b".repeat(40)));
        AnalysisInputs inputs = new AnalysisInputs(
                IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                List.of(new AnalysisInputs.Project(
                        "module-a",
                        DIGEST,
                        Map.of(),
                        List.of(),
                        List.of(
                                new AnalysisInputs.Root("module-a/src/main/java", "SOURCE", true, List.of()),
                                new AnalysisInputs.Root("module-a/src/main/resources", "SOURCE", true, List.of()),
                                new AnalysisInputs.Root("module-a/src/generated/java", "SOURCE", false, List.of("generated")),
                                new AnalysisInputs.Root("module-a/src/test/java", "SOURCE", false, List.of("test"))),
                        List.of(),
                        List.of())));

        assertThat(DefaultRepositoryAnalysisPreparation.includedSourceRoots(snapshot, inputs))
                .containsExactly(productionRoot, resourceRoot.getParent());
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
