package com.java.semantic.trie;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiRouteApplicationServiceTest {

    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");

    @Mock
    private ApiTrieService apiTrieService;

    private ApiRouteApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApiRouteApplicationService(apiTrieService);
    }

    @Test
    void should_preserve_lookup_match_reasons_and_observations() {
        ApiEntryPointRef ref = ref("orders", SHA_ONE.value(), "OrderController", "/orders/{id}");
        ApiRouteMatchBatch batch = new ApiRouteMatchBatch(
                List.of(new ApiRouteMatch(ref, List.of(
                        ApiRouteMatchReason.TEMPLATE_MATCH,
                        ApiRouteMatchReason.HTTP_METHOD_MATCH))),
                List.of());
        when(apiTrieService.lookupMatches("/orders/42", "GET", "orders")).thenReturn(batch);

        assertThat(service.lookupMatches(
                "/orders/42",
                Optional.of("GET"),
                Optional.of(RepositoryId.of("orders"))))
                .isSameAs(batch);
        verify(apiTrieService).lookupMatches("/orders/42", "GET", "orders");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void should_reject_suggestion_limit_outside_contract(int limit) {
        assertThatThrownBy(() -> service.suggestMatches(
                "/orders", Optional.empty(), Optional.empty(), limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_preserve_suggestion_truncation_observation() {
        ApiRouteMatchBatch batch = new ApiRouteMatchBatch(
                List.of(),
                List.of(new ApiRouteObservation(
                        ApiRouteObservationCode.TRUNCATED_CANDIDATES,
                        "route candidates were truncated by the requested limit")));
        when(apiTrieService.suggestMatches("/order/42", "POST", "orders", 2)).thenReturn(batch);

        assertThat(service.suggestMatches(
                "/order/42",
                Optional.of("POST"),
                Optional.of(RepositoryId.of("orders")),
                2)).isSameAs(batch);
        verify(apiTrieService).suggestMatches("/order/42", "POST", "orders", 2);
    }

    private static ApiEntryPointRef ref(
            String repoId,
            String revision,
            String className,
            String routeTemplate) {
        return new ApiEntryPointRef(
                repoId,
                revision,
                "com.acme.order",
                className,
                "handle",
                "POST",
                routeTemplate,
                unresolved());
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }
}
