package com.java.semantic.query.application;

import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.GitEvidenceProperties;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.MongoException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitEvidenceReadServiceFailureTest {
    @Test
    void maps_a_storage_outage_while_authorizing_to_the_shared_unavailable_error() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getCollection(anyString())).thenThrow(new MongoException("credentials and internal host"));
        ReadPolicyProperties properties = new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of());
        ConfiguredReadPolicy policy = new ConfiguredReadPolicy(properties, new GitEvidenceProperties(List.of("orders")));
        GitEvidenceReadService service = new GitEvidenceReadService(template, policy, Duration.ofSeconds(2),
                new CodeFactReadService(template, new SelectedGenerationGuard(template, policy, Duration.ofSeconds(2)), Duration.ofSeconds(2)));

        assertThatThrownBy(() -> service.listGitBranches(new SemanticQueryContract.GitBranchRequest("orders",
                new SemanticQueryContract.PageRequest(Optional.empty(), 20))))
                .isInstanceOf(SemanticIndexUnavailableException.class);
    }
}
