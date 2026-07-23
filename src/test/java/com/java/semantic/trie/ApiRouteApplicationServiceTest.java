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
    void should_map_lookup_refs_by_named_accessors_and_preserve_candidate_revision() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "orders",
                SHA_ONE.value(),
                "com.acme.order",
                "OrderController",
                "getOrder",
                "GET",
                "/orders/{*}",
                unresolved());
        when(apiTrieService.lookupCandidates("/orders/42", "GET", "orders"))
                .thenReturn(List.of(ref));

        List<ApiRouteCandidate> candidates = service.lookup(
                "/orders/42",
                Optional.of("GET"),
                Optional.of(RepositoryId.of("orders")));

        assertThat(candidates).containsExactly(new ApiRouteCandidate(
                "orders",
                SHA_ONE.value(),
                "GET",
                "/orders/{*}",
                "com.acme.order",
                "OrderController",
                "getOrder",
                unresolved()));
    }

    @Test
    void should_return_empty_list_when_trie_has_no_lookup_candidate() {
        when(apiTrieService.lookupCandidates("/missing", "", "")).thenReturn(List.of());

        assertThat(service.lookup("/missing", Optional.empty(), Optional.empty())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void should_reject_suggestion_limit_outside_contract(int limit) {
        assertThatThrownBy(() -> service.suggest(
                "/orders", Optional.empty(), Optional.empty(), limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_forward_optional_method_scope_and_limit_to_suggest_without_reordering() {
        ApiEntryPointRef first = ref("orders", SHA_ONE.value(), "FirstController", "/orders/{id}");
        ApiEntryPointRef second = ref("orders", SHA_ONE.value(), "SecondController", "/orders/{*}");
        when(apiTrieService.suggestCandidates("/order/42", "POST", "orders", 2))
                .thenReturn(List.of(first, second));

        List<ApiRouteCandidate> result = service.suggest(
                "/order/42",
                Optional.of("POST"),
                Optional.of(RepositoryId.of("orders")),
                2);

        assertThat(result).containsExactly(
                ApiRouteCandidate.from(first),
                ApiRouteCandidate.from(second));
        verify(apiTrieService).suggestCandidates("/order/42", "POST", "orders", 2);
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
