package com.java.semantic.indexer.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerOpenApiContractTest {
    @Test
    void documents_the_single_process_job_contract_without_flat_target_or_ownership_fields() throws IOException {
        String document = openApi();
        String jobResponse = schema(document, "IndexJobResponse");
        String jobStatus = schema(document, "IndexJobStatusResponse");
        String target = schema(document, "IndexJobTarget");

        assertThat(jobResponse).contains("required: [jobId, repositoryId, phase]");
        assertThat(jobResponse).contains("target: { $ref: '#/components/schemas/IndexJobTarget', nullable: true }");
        assertThat(jobResponse).contains("enum: [ACCEPTED, RUNNING, COMPLETE, FAILED]");
        assertThat(jobStatus).contains("required: [jobId, repositoryId, operation, phase, active]");
        assertThat(jobStatus).contains("target: { $ref: '#/components/schemas/IndexJobTarget', nullable: true }");
        assertThat(jobStatus).contains("operation: { type: string, enum: [BUILD, ROLLBACK, RESET, NO_WORK] }");
        assertThat(jobStatus).contains("phase: { type: string, enum: [ACCEPTED, RUNNING, COMPLETE, FAILED] }");

        assertThat(jobResponse).doesNotContain("revision: {", "generationId: {", "generation: {");
        assertThat(jobStatus).doesNotContain("revision: {", "generationId: {", "generation: {");
        assertThat(target).contains("required: [revision, generationId, generation]");
        assertThat(target).contains("revision: { type: string, pattern: '^[0-9a-f]{40}$' }");
        assertThat(target).contains("generationId: { type: string }");
        assertThat(target).contains("generation: { type: integer, format: int64, minimum: 1 }");
        assertThat(withoutUatPaths(document)).doesNotContain("worker" + "Id", "claim" + "Until", "heart" + "beat", "fen" + "ce", "lea" + "se");
        assertThat(document).contains("/index/uat/publication/arm:", "/index/uat/publication/await:",
                "/index/uat/publication/release:", "/index/uat/repositories/{repoId}/reset:",
                "operationId: awaitUatPublication", "operationId: resetUatRepositoryIndex");

        assertThat(recordComponentNames(IndexRepositoryController.IndexJobResponse.class))
                .containsExactly("jobId", "repositoryId", "target", "phase", "failureCategory");
        assertThat(recordComponentNames(IndexRepositoryController.IndexJobStatusResponse.class))
                .containsExactly("jobId", "repositoryId", "target", "operation", "phase", "active", "failureCategory", "currentPointer");
    }

    private static String openApi() throws IOException {
        InputStream resource = Objects.requireNonNull(
                IndexerOpenApiContractTest.class.getResourceAsStream("/openapi/semantic-indexer-api-v1.yaml"),
                "OpenAPI document is required");
        try (InputStream input = resource) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String schema(String document, String name) {
        Pattern pattern = Pattern.compile("(?ms)^    " + Pattern.quote(name) + ":\\R(.*?)(?=^    [A-Za-z][A-Za-z0-9]*:|\\z)");
        Matcher matcher = pattern.matcher(document);
        assertThat(matcher.find()).as("schema %s", name).isTrue();
        return matcher.group(1);
    }

    private static String withoutUatPaths(String document) {
        return document.replaceAll("(?ms)^  /index/uat/.*?(?=^  /index/repositories/)", "");
    }

    private static List<String> recordComponentNames(Class<?> recordType) {
        List<String> result = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            result.add(component.getName());
        }
        return result;
    }
}
