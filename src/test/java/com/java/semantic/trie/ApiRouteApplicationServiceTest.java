package com.java.semantic.trie;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiRouteApplicationServiceTest {

    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");

    @Mock
    private ApiTrieService apiTrieService;

    @Mock
    private RepositoryApplicationService repositoryApplicationService;

    private ApiRouteApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApiRouteApplicationService(apiTrieService, repositoryApplicationService);
    }

    @Test
    void should_preserve_lookup_match_reasons_and_observations() {
        ApiEntryPointRef ref = ref("orders", SHA_ONE.value(), "OrderController", "/orders/{id}");
        ApiRouteMatchBatch batch = new ApiRouteMatchBatch(
                List.of(new ApiRouteMatch(ref, List.of(
                        ApiRouteMatchReason.TEMPLATE_MATCH,
                        ApiRouteMatchReason.HTTP_METHOD_MATCH))),
                List.of());
        RepositoryId repositoryId = RepositoryId.of("orders");
        when(repositoryApplicationService.withSnapshot(
                eq(repositoryId), eq(Optional.of(SHA_ONE)), any())).thenReturn(batch);

        assertThat(service.lookupMatches(
                repositoryId,
                SHA_ONE,
                "/orders/42",
                Optional.of("GET")))
                .isSameAs(batch);
        verify(repositoryApplicationService).withSnapshot(
                eq(repositoryId), eq(Optional.of(SHA_ONE)), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void should_reject_suggestion_limit_outside_contract(int limit) {
        assertThatThrownBy(() -> service.suggestMatches(
                RepositoryId.of("orders"), SHA_ONE, "/orders", Optional.empty(), limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_preserve_suggestion_truncation_observation() {
        ApiRouteMatchBatch batch = new ApiRouteMatchBatch(
                List.of(),
                List.of(new ApiRouteObservation(
                        ApiRouteObservationCode.TRUNCATED_CANDIDATES,
                        "route candidates were truncated by the requested limit")));
        RepositoryId repositoryId = RepositoryId.of("orders");
        when(repositoryApplicationService.withSnapshot(
                eq(repositoryId), eq(Optional.of(SHA_ONE)), any())).thenReturn(batch);

        assertThat(service.suggestMatches(
                repositoryId,
                SHA_ONE,
                "/order/42",
                Optional.of("POST"),
                2)).isSameAs(batch);
        verify(repositoryApplicationService).withSnapshot(
                eq(repositoryId), eq(Optional.of(SHA_ONE)), any());
    }

    @Test
    void should_not_query_stale_route_index_after_repository_snapshot_accepts_revision() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositorySnapshot snapshot = new RepositorySnapshot(
                repositoryId, Path.of(".").toAbsolutePath().normalize(), SHA_ONE);
        when(repositoryApplicationService.withSnapshot(
                eq(repositoryId), eq(Optional.of(SHA_ONE)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, ApiRouteMatchBatch> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(apiTrieService.lookupMatches(repositoryId, SHA_ONE, "/orders/42", "GET"))
                .thenThrow(new ApiRouteIndexNotReadyException(repositoryId, SHA_ONE));

        assertThatThrownBy(() -> service.lookupMatches(
                repositoryId, SHA_ONE, "/orders/42", Optional.of("GET")))
                .isInstanceOf(ApiRouteIndexNotReadyException.class);
    }


    private static ApiEntryPointRef ref(
            String repoId,
            String revision,
            String className,
            String routeTemplate) {
        return new ApiEntryPointRef(
                repoId,
                revision,
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", className),
                        "src/main/java/com/acme/order/" + className + ".java"),
                "handle",
                "POST",
                routeTemplate,
                unresolved());
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }
}
