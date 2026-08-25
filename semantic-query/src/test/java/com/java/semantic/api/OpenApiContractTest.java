package com.java.semantic.api;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void advertises_the_exact_flat_requests_security_typed_errors_and_generation_envelopes() throws Exception {
        String specification = Files.readString(Path.of("src", "main", "resources", "openapi", "semantic-api-v1.yaml"));

        assertThat(specification).contains("security:", "ApiToken:", "name: X-Api-Token")
                .doesNotContain("allOf:")
                .contains("SearchCodeFactsRequest:", "GetCodeFactRequest:", "CallGraphRequest:", "RelationRequest:",
                        "EventListenerRequest:", "TypeMemberRequest:", "SourceSymbolRequest:", "SourceSegmentRequest:")
                .contains("additionalProperties: false", "required: [repositoryId, revision, result]",
                        "RevisionOutdatedError:", "QueryFailure:", "repositoryId", "requestedRevision", "currentRevision",
                        "/v1/analyses/call-graphs/outgoing:", "/v1/discovery/type-members:",
                        "/v1/discovery/source-symbols/resolve:", "/v1/api-routes/lookup:")
                .contains("'400': {$ref: '#/components/responses/RequestInvalid'}",
                        "'404': {$ref: '#/components/responses/QueryNotFound'}",
                        "'409': {$ref: '#/components/responses/RevisionOutdated'}",
                        "'503': {$ref: '#/components/responses/QueryUnavailable'}");
    }
}
