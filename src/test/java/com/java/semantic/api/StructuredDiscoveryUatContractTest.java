package com.java.semantic.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 靜態驗證 manual UAT 使用可直接執行的 mapper mapping 契約 */
class StructuredDiscoveryUatContractTest {

    @Test
    void should_follow_resolved_mapper_projection_without_reconstructing_method_identity()
            throws IOException {
        Path uatFile = Path.of(
                System.getProperty("basedir"),
                "uat",
                "structured-concept-discovery.http");
        String scenario = Files.readString(uatFile);

        assertThat(scenario)
                .contains("\"matchMode\": \"TOKEN_EXACT\"")
                .doesNotContain("\"matchMode\": \"EXACT_TOKEN\"")
                .contains("candidate.mapperStatementMapping?.status === \"RESOLVED\"")
                .contains("item => item.operation === \"GET_METHOD_SQL\"")
                .contains("JSON.stringify(methodSql.request)")
                .doesNotContain("candidate.kind === \"MAPPER_STATEMENT\" && candidate.target");
    }
}
