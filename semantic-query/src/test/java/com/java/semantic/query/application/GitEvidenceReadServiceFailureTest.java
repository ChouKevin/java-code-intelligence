package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.MongoException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitEvidenceReadServiceFailureTest {
    @Test
    void maps_a_storage_outage_while_authorizing_to_the_shared_unavailable_error() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getCollection(IndexCollections.REPOSITORIES)).thenThrow(new MongoException("storage unavailable"));
        ReadPolicyProperties properties = new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of(), List.of());
        GitEvidenceReadService service = new GitEvidenceReadService(template, new ConfiguredReadPolicy(properties), Duration.ofSeconds(2));

        assertThatThrownBy(() -> service.branches(new SemanticQueryContract.GitBranchRequest("orders",
                Optional.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), 0, 20)))
                .isInstanceOf(SemanticIndexUnavailableException.class);
    }
}
