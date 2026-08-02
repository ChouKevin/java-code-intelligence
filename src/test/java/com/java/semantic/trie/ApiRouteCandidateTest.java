package com.java.semantic.trie;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ApiRouteCandidateTest {

    private static final String REVISION =
            "1111111111111111111111111111111111111111";

    @Test
    void should_preserve_every_field_when_candidate_is_mapped_from_route_match() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "repo-a",
                REVISION,
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.orders", "OrderController"),
                        "src/main/java/com/example/orders/OrderController.java"),
                "findOrder",
                "GET",
                "/orders/{*}",
                unresolved());

        ApiRouteCandidate candidate = ApiRouteCandidate.from(new ApiRouteMatch(
                ref,
                List.of(ApiRouteMatchReason.TEMPLATE_MATCH, ApiRouteMatchReason.HTTP_METHOD_MATCH)));

        assertThat(candidate).isEqualTo(new ApiRouteCandidate(
                "repo-a",
                REVISION,
                "GET",
                "/orders/{*}",
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.orders", "OrderController"),
                        "src/main/java/com/example/orders/OrderController.java"),
                "findOrder",
                unresolved(),
                List.of(ApiRouteMatchReason.TEMPLATE_MATCH, ApiRouteMatchReason.HTTP_METHOD_MATCH)));
    }

    @Test
    void should_preserve_analysis_target_identity_when_candidate_is_mapped_from_trie_ref() {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.orders", "OrderController"),
                        "src/main/java/com/example/orders/OrderController.java"),
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
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.orders", "OrderController"),
                        "src/main/java/com/example/orders/OrderController.java"),
                "",
                List.of(),
                List.of(entryPoint));
        ApiTrieService service = new ApiTrieService();
        RepositorySnapshot snapshot = new RepositorySnapshot(
                RepositoryId.of("repo-a"),
                Path.of(".").toAbsolutePath().normalize(),
                RepositoryRevision.ofSha(REVISION));

        service.reload(snapshot, new RepositorySyntax(List.of(entryPointClass), List.of()));

        ApiRouteMatch match = service.lookupMatches("/orders/123", "GET", "").matches().getFirst();
        ApiRouteCandidate candidate = ApiRouteCandidate.from(match);

        assertThat(candidate.analysisTarget()).isSameAs(resolution);
        assertThat(candidate.analysisTarget().target()).get().isSameAs(target);
    }

    @Test
    void should_reject_a_resolved_target_that_diverges_from_the_declared_route_identity() {
        SourceTypeIdentity routeSourceType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example.orders", "OrderController"),
                "src/main/java/com/example/orders/OrderController.java");
        MethodTarget divergentTarget = new MethodTarget(
                routeSourceType,
                "listOrders",
                List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> new ApiRouteCandidate(
                "repo-a",
                REVISION,
                "GET",
                "/orders/{*}",
                routeSourceType,
                "findOrder",
                MethodTargetResolution.resolved(divergentTarget),
                List.of()))
                .withMessage("resolved target must match route source type and method name");
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }
}
