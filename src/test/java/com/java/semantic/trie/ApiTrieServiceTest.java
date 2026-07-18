package com.java.semantic.trie;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ApiTrieServiceTest {

    @Test
    void should_prefer_exact_then_wildcard_then_rest_when_multiple_paths_match() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(
                route("ExactController", "exact", "GET", "/api/special"),
                route("WildcardController", "wildcard", "GET", "/api/{id}"),
                route("RestController", "rest", "GET", "/api/{*path}")));

        assertThat(service.lookupCandidates("/api/special", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("exact");
        assertThat(service.lookupCandidates("/api/other", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("wildcard");
        assertThat(service.lookupCandidates("/api/other/more", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("rest");
    }

    @Test
    void should_backtrack_to_wildcard_when_exact_path_has_no_requested_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(
                route("ExactController", "getExact", "GET", "/api/vip/special"),
                route("WildcardController", "postWildcard", "POST", "/api/vip/{id}")));

        assertThat(service.lookupCandidates("/api/vip/special", "POST", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("postWildcard");
    }

    @Test
    void should_match_zero_or_many_segments_when_route_uses_terminal_rest_wildcard() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(route("FileController", "read", "GET", "/files/{*path}")));

        assertThat(service.lookupCandidates("/files", "GET", "")).hasSize(1);
        assertThat(service.lookupCandidates("/files/a/b/c", "GET", "")).hasSize(1);
    }

    @Test
    void should_not_descend_when_rest_wildcard_has_registered_children() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(route(
                "FileController", "metadata", "GET", "/files/{*path}/metadata")));

        assertThat(service.lookupCandidates("/files/a/metadata", "GET", "")).isEmpty();
    }

    @Test
    void should_use_all_only_as_fallback_when_lookup_has_exact_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo-all", syntax(route("AllController", "all", "ALL", "/orders")));
        service.reload("repo-get", syntax(route("GetController", "get", "GET", "/orders")));

        assertThat(service.lookupCandidates("/orders", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("get");
        assertThat(service.lookupCandidates("/orders", "POST", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("all");
    }

    @Test
    void should_union_all_with_exact_method_when_suggesting_routes() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo-all", syntax(route("AllController", "all", "ALL", "/orders/{id}")));
        service.reload("repo-get", syntax(route("GetController", "get", "GET", "/orders/{id}")));

        assertThat(service.suggestCandidates("/orders/42", "GET", "", 10))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("all", "get");
    }

    @Test
    void should_publish_repo_routes_when_rebuild_contains_trace_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(
                route("TraceController", "trace", "TRACE", "/diagnostics"),
                route("HealthController", "health", "GET", "/health")));

        assertThat(service.lookupCandidates("/diagnostics", "TRACE", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("trace");
        assertThat(service.lookupCandidates("/health", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("health");
    }

    @Test
    void should_keep_lexicographically_smallest_handler_when_same_repo_route_and_method_collide(
            CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass later = route("ZController", "zHandler", "GET", "/collision");
        EntryPointClass earlier = route("AController", "aHandler", "GET", "/collision");
        service.reload("repo", syntax(later, earlier));

        assertThat(service.lookupCandidates("/collision", "GET", ""))
                .extracting(ApiEntryPointRef::className, ApiEntryPointRef::methodName)
                .containsExactly(Tuple.tuple("AController", "aHandler"));
        assertThat(service.suggestCandidates("/collision", "GET", "", 10))
                .extracting(ApiEntryPointRef::className, ApiEntryPointRef::methodName)
                .containsExactly(Tuple.tuple("AController", "aHandler"));
        assertThat(output).contains("AController.aHandler", "ZController.zHandler");
    }

    @Test
    void should_keep_smaller_package_when_collision_differs_in_package_and_later_keys() {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass largerPackage = route(
                "z.package", "AController", "aHandler", "GET", "/package-collision");
        EntryPointClass smallerPackage = route(
                "a.package", "ZController", "zHandler", "GET", "/package-collision");

        service.reload("repo", syntax(largerPackage, smallerPackage));

        assertThat(service.lookupCandidates("/package-collision", "GET", ""))
                .extracting(ApiEntryPointRef::packageName)
                .containsExactly("a.package");
        assertThat(service.suggestCandidates("/package-collision", "GET", "", 10))
                .extracting(ApiEntryPointRef::packageName)
                .containsExactly("a.package");
    }

    @Test
    void should_include_package_when_collision_handlers_share_class_and_method(CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass first = route(
                "z.package", "SameController", "sameHandler", "GET", "/identity-collision");
        EntryPointClass second = route(
                "a.package", "SameController", "sameHandler", "GET", "/identity-collision");

        service.reload("repo", syntax(first, second));

        assertThat(output)
                .contains("z.package.SameController.sameHandler")
                .contains("a.package.SameController.sameHandler");
    }

    @Test
    void should_keep_smaller_method_when_collision_matches_through_class_name() {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass largerMethod = route(
                "same.package", "SameController", "zHandler", "GET", "/method-collision");
        EntryPointClass smallerMethod = route(
                "same.package", "SameController", "aHandler", "GET", "/method-collision");

        service.reload("repo", syntax(largerMethod, smallerMethod));

        assertThat(service.lookupCandidates("/method-collision", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("aHandler");
        assertThat(service.suggestCandidates("/method-collision", "GET", "", 10))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("aHandler");
    }

    @Test
    void should_order_cross_repo_candidates_when_same_route_exists_in_multiple_repositories() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo-b", syntax(route("BController", "fromB", "GET", "/shared")));
        service.reload("repo-a", syntax(route("AController", "fromA", "GET", "/shared")));

        assertThat(service.lookupCandidates("/shared", "GET", ""))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-a", "repo-b");
        assertThat(service.lookupCandidates("/shared", "GET", "repo-b"))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-b");
    }

    @Test
    void should_restore_previous_routes_and_propagate_when_rebuild_fails() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(route("OldController", "old", "GET", "/old")));

        assertThatThrownBy(() -> service.reload("repo", () -> {
            throw new IllegalStateException("syntax failed");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("syntax failed");
        assertThat(service.lookupCandidates("/old", "GET", "")).hasSize(1);
    }

    @Test
    void should_omit_nonterminal_rest_route_when_repo_has_valid_sibling(CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(
                route("InvalidController", "invalid", "GET", "/files/{*path}/metadata"),
                route("ValidController", "valid", "GET", "/files/valid")));

        assertThat(service.lookupCandidates("/files/a/metadata", "GET", "")).isEmpty();
        assertThat(service.suggestCandidates("/files/a/metadata", "GET", "", 10))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("valid");
        assertThat(service.lookupCandidates("/files/valid", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("valid");
        assertThat(output).contains("InvalidController.invalid", "nonterminal");
    }

    @Test
    void should_return_other_repo_without_stale_repo_when_one_repo_write_lock_and_rebuild_are_blocked()
            throws Exception {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo-a", syntax(route("OldController", "old", "GET", "/shared")));
        service.reload("repo-b", syntax(route("OtherController", "other", "GET", "/shared")));
        ReentrantReadWriteLock repoALock = new ReentrantReadWriteLock(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> rebuild = CompletableFuture.runAsync(() -> {
            repoALock.writeLock().lock();
            try {
                service.reload("repo-a", () -> {
                    started.countDown();
                    await(release);
                    return syntax(route("NewController", "newRoute", "GET", "/shared"));
                });
            } finally {
                repoALock.writeLock().unlock();
            }
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repoALock.isWriteLocked()).isTrue();
        assertThat(service.lookupCandidates("/shared", "GET", ""))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-b");
        release.countDown();
        rebuild.get(5, TimeUnit.SECONDS);
        assertThat(service.lookupCandidates("/shared", "GET", ""))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("newRoute", "other");
    }

    @Test
    void should_hide_partial_repo_when_rebuild_is_blocked_during_state_construction() throws Exception {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo-a", syntax(route("OldController", "old", "GET", "/publication")));
        service.reload("repo-b", syntax(route("OtherController", "other", "GET", "/publication")));
        CountDownLatch supplierReturned = new CountDownLatch(1);
        CountDownLatch constructionBlocked = new CountDownLatch(1);
        CountDownLatch releaseConstruction = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(ApiTrieService.class);
        BlockingCollisionAppender appender = new BlockingCollisionAppender(
                "/publication", constructionBlocked, releaseConstruction);
        appender.start();
        try {
            logger.addAppender(appender);
            CompletableFuture<Void> rebuild = CompletableFuture.runAsync(() -> service.reload("repo-a", () -> {
                supplierReturned.countDown();
                return syntax(route("NewController", "newRoute", "GET", "/publication"));
            }));
            try {
                assertThat(supplierReturned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(constructionBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.lookupCandidates("/publication", "GET", ""))
                        .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::methodName)
                        .containsExactly(Tuple.tuple("repo-b", "other"));
            } finally {
                releaseConstruction.countDown();
                rebuild.get(5, TimeUnit.SECONDS);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(service.lookupCandidates("/publication", "GET", ""))
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::methodName)
                .containsExactly(
                        Tuple.tuple("repo-a", "newRoute"),
                        Tuple.tuple("repo-b", "other"));
    }

    @Test
    void should_return_first_candidate_when_compatibility_lookup_is_used() {
        ApiTrieService service = new ApiTrieService();
        service.reload("repo", syntax(route("Controller", "handler", "GET", "/route")));

        Optional<ApiEntryPointRef> result = service.lookup("/route", "GET");

        assertThat(result).get().extracting(ApiEntryPointRef::methodName).isEqualTo("handler");
    }

    private static RepositorySyntax syntax(EntryPointClass... classes) {
        return new RepositorySyntax(List.of(classes), List.of());
    }

    private static EntryPointClass route(
            String className,
            String methodName,
            String httpMethod,
            String path) {
        return route("com.example", className, methodName, httpMethod, path);
    }

    private static EntryPointClass route(
            String packageName,
            String className,
            String methodName,
            String httpMethod,
            String path) {
        List<EntryPointMethod> methods = List.of(new ApiEntryPoint(
                methodName, "", path, List.of(httpMethod), List.of()));
        return new EntryPointClass(
                className,
                packageName,
                packageName.replace('.', '/') + "/" + className + ".java",
                "",
                List.of(),
                methods);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out awaiting test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting test latch", exception);
        }
    }

    private static final class BlockingCollisionAppender extends AppenderBase<ILoggingEvent> {

        private final String route;
        private final CountDownLatch blocked;
        private final CountDownLatch release;

        private BlockingCollisionAppender(
                String route,
                CountDownLatch blocked,
                CountDownLatch release) {
            this.route = route;
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().contains(route)) {
                blocked.countDown();
                await(release);
            }
        }
    }
}
