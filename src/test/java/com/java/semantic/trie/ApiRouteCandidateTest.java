package com.java.semantic.trie;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateTest {

    private static final String REVISION =
            "1111111111111111111111111111111111111111";

    @Test
    void should_preserve_every_field_when_candidate_is_mapped_from_entry_point_ref() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "repo-a",
                REVISION,
                "com.example.orders",
                "OrderController",
                "findOrder",
                "GET",
                "/orders/{*}",
                unresolved());

        ApiRouteCandidate candidate = ApiRouteCandidate.from(ref);

        assertThat(candidate).isEqualTo(new ApiRouteCandidate(
                "repo-a",
                REVISION,
                "GET",
                "/orders/{*}",
                "com.example.orders",
                "OrderController",
                "findOrder",
                unresolved()));
    }

    @Test
    void should_preserve_analysis_target_identity_when_candidate_is_mapped_from_trie_ref() {
        MethodTarget target = new MethodTarget(
                "src/main/java/com/example/orders/OrderController.java",
                "com.example.orders",
                "OrderController",
                "findOrder",
                List.of("java.lang.String"));
        MethodTargetResolution resolution = MethodTargetResolution.resolved(target);
        ApiEntryPoint entryPoint = new ApiEntryPoint(
                "findOrder",
                "",
                "/orders/{id}",
                List.of("GET"),
                List.of(),
                resolution);
        EntryPointClass entryPointClass = new EntryPointClass(
                "OrderController",
                "com.example.orders",
                "com/example/orders/OrderController.java",
                "",
                List.of(),
                List.of(entryPoint));
        ApiTrieService service = new ApiTrieService();
        RepositorySnapshot snapshot = new RepositorySnapshot(
                RepositoryId.of("repo-a"),
                Path.of(".").toAbsolutePath().normalize(),
                RepositoryRevision.ofSha(REVISION));

        service.reload(snapshot, new RepositorySyntax(List.of(entryPointClass), List.of()));

        ApiEntryPointRef ref = service.lookup("/orders/123", "GET").orElseThrow();
        ApiRouteCandidate candidate = ApiRouteCandidate.from(ref);

        assertThat(candidate.analysisTarget()).isSameAs(resolution);
        assertThat(candidate.analysisTarget().target()).get().isSameAs(target);
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }
}
