package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.java.semantic.mcp.ToolProjectionCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Keeps Query requirements tied to the production mapper and validation dispatchers. */
@Tag("jdtls-it")
class ToolProjectionImplementationContractTest {

    @Test
    void every_query_projection_is_produced_validated_counted_and_digested_by_indexer_dispatchers() {
        assertThatCode(() -> ToolProjectionCatalog.validateImplementationCoverage(
                SourceIndexBatchDocumentMapper.producedProjections(),
                GenerationValidator.validatedCountedAndDigestedProjections()))
                .doesNotThrowAnyException();
    }
}
