package com.java.semantic.trie;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.support.ConcurrencyTestSupport;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(OutputCaptureExtension.class)
class ApiTrieServiceTest {

    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");

    @Test
    void should_prefer_exact_then_wildcard_then_rest_when_multiple_paths_match() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(
                route("ExactController", "exact", "GET", "/api/special"),
                route("WildcardController", "wildcard", "GET", "/api/{id}"),
                route("RestController", "rest", "GET", "/api/{*path}")));

        assertThat(refs(service.lookupMatches("/api/special", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("exact");
        assertThat(refs(service.lookupMatches("/api/other", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("wildcard");
        assertThat(refs(service.lookupMatches("/api/other/more", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("rest");
    }

    @Test
    void should_backtrack_to_wildcard_when_exact_path_has_no_requested_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(
                route("ExactController", "getExact", "GET", "/api/vip/special"),
                route("WildcardController", "postWildcard", "POST", "/api/vip/{id}")));

        assertThat(refs(service.lookupMatches("/api/vip/special", "POST", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("postWildcard");
    }

    @Test
    void should_match_zero_or_many_segments_when_route_uses_terminal_rest_wildcard() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()),
                syntax(route("FileController", "read", "GET", "/files/{*path}")));

        assertThat(refs(service.lookupMatches("/files", "GET", ""))).hasSize(1);
        assertThat(refs(service.lookupMatches("/files/a/b/c", "GET", ""))).hasSize(1);
    }

    @Test
    void should_not_descend_when_rest_wildcard_has_registered_children() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(route(
                "FileController", "metadata", "GET", "/files/{*path}/metadata")));

        assertThat(refs(service.lookupMatches("/files/a/metadata", "GET", ""))).isEmpty();
    }

    @Test
    void should_use_all_only_as_fallback_when_lookup_has_exact_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-all", SHA_ONE.value()), syntax(route("AllController", "all", "ALL", "/orders")));
        service.reload(snapshot("repo-get", SHA_TWO.value()), syntax(route("GetController", "get", "GET", "/orders")));

        assertThat(refs(service.lookupMatches("/orders", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("get");
        assertThat(refs(service.lookupMatches("/orders", "POST", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("all");
    }

    @Test
    void should_union_all_with_exact_method_when_suggesting_routes() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-all", SHA_ONE.value()),
                syntax(route("AllController", "all", "ALL", "/orders/{id}")));
        service.reload(snapshot("repo-get", SHA_TWO.value()),
                syntax(route("GetController", "get", "GET", "/orders/{id}")));

        assertThat(refs(service.suggestMatches("/orders/42", "GET", "", 10)))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("all", "get");
    }

    @Test
    void should_exclude_suggestion_with_only_same_segment_count_reason() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()),
                syntax(route("ArchiveController", "find", "GET", "/archive/{id}")));

        assertThat(service.suggestMatches("/orders/42", "GET", "", 10).matches()).isEmpty();
    }

    @Test
    void should_return_typed_lookup_match_reasons() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()),
                syntax(route("OrderController", "get", "GET", "/orders/{id}")));

        assertThat(service.lookupMatches("/orders/42", "GET", "").matches())
                .singleElement()
                .extracting(ApiRouteMatch::matchReasons)
                .isEqualTo(List.of(
                        ApiRouteMatchReason.TEMPLATE_MATCH,
                        ApiRouteMatchReason.HTTP_METHOD_MATCH));
    }

    @Test
    void should_use_canonical_order_and_report_suggestion_truncation_across_reloads() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-z", SHA_TWO.value()),
                syntax(route("ZController", "z", "GET", "/orders/{id}")));
        service.reload(snapshot("repo-a", SHA_ONE.value()),
                syntax(route("AController", "a", "GET", "/orders/{*path}")));

        ApiRouteMatchBatch beforeReload = service.suggestMatches("/orders/42", "GET", "", 1);
        service.reload(snapshot("repo-a", SHA_ONE.value()),
                syntax(route("AController", "a", "GET", "/orders/{*path}")));
        ApiRouteMatchBatch afterReload = service.suggestMatches("/orders/42", "GET", "", 1);

        assertThat(beforeReload.matches()).extracting(match -> match.ref().repoId())
                .containsExactly("repo-a");
        assertThat(afterReload.matches()).isEqualTo(beforeReload.matches());
        assertThat(beforeReload.matches().get(0).matchReasons()).containsExactly(
                ApiRouteMatchReason.SHARED_STATIC_SEGMENT,
                ApiRouteMatchReason.POSITIONAL_STATIC_SEGMENT,
                ApiRouteMatchReason.SAME_SEGMENT_COUNT);
        assertThat(beforeReload.observations()).containsExactly(new ApiRouteObservation(
                ApiRouteObservationCode.TRUNCATED_CANDIDATES,
                "route candidates were truncated by the requested limit"));
    }

    @Test
    void should_not_report_truncation_when_suggestion_candidate_count_equals_limit() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-a", SHA_ONE.value()),
                syntax(route("AController", "a", "GET", "/orders/{id}")));
        service.reload(snapshot("repo-b", SHA_TWO.value()),
                syntax(route("BController", "b", "GET", "/orders/{*path}")));

        ApiRouteMatchBatch batch = service.suggestMatches("/orders/42", "GET", "", 2);

        assertThat(batch.matches()).extracting(match -> match.ref().repoId())
                .containsExactly("repo-a", "repo-b");
        assertThat(batch.observations()).isEmpty();
    }

    @Test
    void should_publish_repo_routes_when_rebuild_contains_trace_method() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(
                route("TraceController", "trace", "TRACE", "/diagnostics"),
                route("HealthController", "health", "GET", "/health")));

        assertThat(refs(service.lookupMatches("/diagnostics", "TRACE", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("trace");
        assertThat(refs(service.lookupMatches("/health", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("health");
    }

    @Test
    void should_keep_lexicographically_smallest_handler_when_same_repo_route_and_method_collide(
            CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass later = route("ZControllerSentinel", "zHandlerSentinel", "GET", "/collision-sentinel");
        EntryPointClass earlier = route("AControllerSentinel", "aHandlerSentinel", "GET", "/collision-sentinel");
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(later, earlier));

        assertThat(refs(service.lookupMatches("/collision-sentinel", "GET", "")))
                .extracting(ref -> ref.sourceType().javaType().className(), ApiEntryPointRef::methodName)
                .containsExactly(Tuple.tuple("AControllerSentinel", "aHandlerSentinel"));
        assertThat(refs(service.suggestMatches("/collision-sentinel", "GET", "", 10)))
                .extracting(ref -> ref.sourceType().javaType().className(), ApiEntryPointRef::methodName)
                .containsExactly(Tuple.tuple("AControllerSentinel", "aHandlerSentinel"));
        assertThat(output)
                .contains("repoId=repo", "category=INTRA_REPOSITORY_COLLISION")
                .doesNotContain(
                        "/collision-sentinel",
                        "AControllerSentinel",
                        "aHandlerSentinel",
                        "ZControllerSentinel",
                        "zHandlerSentinel",
                        "com.example");
    }

    @Test
    void should_keep_smaller_package_when_collision_differs_in_package_and_later_keys() {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass largerPackage = route(
                "z.package", "AController", "aHandler", "GET", "/package-collision");
        EntryPointClass smallerPackage = route(
                "a.package", "ZController", "zHandler", "GET", "/package-collision");

        service.reload(snapshot("repo", SHA_ONE.value()), syntax(largerPackage, smallerPackage));

        assertThat(refs(service.lookupMatches("/package-collision", "GET", "")))
                .extracting(ref -> ref.sourceType().javaType().packageName())
                .containsExactly("a.package");
        assertThat(refs(service.suggestMatches("/package-collision", "GET", "", 10)))
                .extracting(ref -> ref.sourceType().javaType().packageName())
                .containsExactly("a.package");
    }

    @Test
    void should_sanitize_collision_log_when_handlers_share_class_and_method(CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass first = route(
                "z.package.sentinel", "SameControllerSentinel", "sameHandlerSentinel",
                "GET", "/identity-collision-sentinel");
        EntryPointClass second = route(
                "a.package.sentinel", "SameControllerSentinel", "sameHandlerSentinel",
                "GET", "/identity-collision-sentinel");

        service.reload(snapshot("repo", SHA_ONE.value()), syntax(first, second));

        assertThat(output)
                .contains("repoId=repo", "category=INTRA_REPOSITORY_COLLISION")
                .doesNotContain(
                        "/identity-collision-sentinel",
                        "z.package.sentinel",
                        "a.package.sentinel",
                        "SameControllerSentinel",
                        "sameHandlerSentinel");
    }

    @Test
    void should_keep_smaller_method_when_collision_matches_through_class_name() {
        ApiTrieService service = new ApiTrieService();
        EntryPointClass largerMethod = route(
                "same.package", "SameController", "zHandler", "GET", "/method-collision");
        EntryPointClass smallerMethod = route(
                "same.package", "SameController", "aHandler", "GET", "/method-collision");

        service.reload(snapshot("repo", SHA_ONE.value()), syntax(largerMethod, smallerMethod));

        assertThat(refs(service.lookupMatches("/method-collision", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("aHandler");
        assertThat(refs(service.suggestMatches("/method-collision", "GET", "", 10)))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("aHandler");
    }

    @Test
    void should_choose_the_same_complete_resolved_target_when_tied_route_display_fields_reverse() {
        MethodTarget smallerTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.a", "OrderHandler"),
                        "src/main/java/com/example/a/OrderHandler.java"),
                "handle",
                List.of("java.lang.Integer"));
        MethodTarget largerTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.z", "OrderHandler"),
                        "src/main/java/com/example/z/OrderHandler.java"),
                "handle",
                List.of("java.lang.String"));
        EntryPointClass smaller = route(
                "SameController",
                "sameHandler",
                "GET",
                "/complete-target-collision",
                MethodTargetResolution.resolved(smallerTarget));
        EntryPointClass larger = route(
                "SameController",
                "sameHandler",
                "GET",
                "/complete-target-collision",
                MethodTargetResolution.resolved(largerTarget));
        ApiTrieService forward = new ApiTrieService();
        ApiTrieService reversed = new ApiTrieService();

        forward.reload(snapshot("repo", SHA_ONE.value()), syntax(larger, smaller));
        reversed.reload(snapshot("repo", SHA_ONE.value()), syntax(smaller, larger));

        assertThat(refs(forward.lookupMatches("/complete-target-collision", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::analysisTarget)
                .extracting(resolution -> resolution.target().orElseThrow())
                .isEqualTo(smallerTarget);
        assertThat(refs(reversed.lookupMatches("/complete-target-collision", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::analysisTarget)
                .extracting(resolution -> resolution.target().orElseThrow())
                .isEqualTo(smallerTarget);
    }

    @Test
    void should_order_cross_repo_candidates_when_same_route_exists_in_multiple_repositories(
            CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-b", SHA_TWO.value()), syntax(route(
                "BControllerSentinel", "fromBSentinel", "GET", "/shared-sentinel")));
        service.reload(snapshot("repo-a", SHA_ONE.value()), syntax(route(
                "AControllerSentinel", "fromASentinel", "GET", "/shared-sentinel")));

        assertThat(refs(service.lookupMatches("/shared-sentinel", "GET", "")))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-a", "repo-b");
        assertThat(refs(service.lookupMatches("/shared-sentinel", "GET", "repo-b")))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-b");
        assertThat(output)
                .contains("category=CROSS_REPOSITORY_COLLISION")
                .doesNotContain(
                        "/shared-sentinel",
                        "repo-a",
                        "repo-b",
                        "AControllerSentinel",
                        "fromASentinel",
                        "BControllerSentinel",
                        "fromBSentinel");
    }

    @Test
    void should_attach_exact_snapshot_revision_to_every_route_reference() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()),
                syntax(route("OrderController", "get", "GET", "/orders/{id}")));

        assertThat(refs(service.lookupMatches("/orders/42", "GET", "")))
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::analyzedRevision)
                .containsExactly(tuple("repo", SHA_ONE.value()));
    }

    @Test
    void should_clear_only_requested_repository_without_rollback() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-a", SHA_ONE.value()),
                syntax(route("AController", "a", "GET", "/shared")));
        service.reload(snapshot("repo-b", SHA_TWO.value()),
                syntax(route("BController", "b", "GET", "/shared")));

        service.clear(RepositoryId.of("repo-a"));

        assertThat(refs(service.lookupMatches("/shared", "GET", "")))
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::analyzedRevision)
                .containsExactly(tuple("repo-b", SHA_TWO.value()));
    }

    @Test
    void should_leave_repository_absent_when_revision_aware_reload_fails() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()),
                syntax(route("OldController", "old", "GET", "/old")));
        service.clear(RepositoryId.of("repo"));

        assertThatThrownBy(() -> service.reload(snapshot("repo", SHA_TWO.value()), () -> {
            throw new IllegalStateException("syntax failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(refs(service.lookupMatches("/old", "GET", ""))).isEmpty();
    }

    @Test
    void should_restore_previous_routes_and_propagate_when_rebuild_fails() {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(route("OldController", "old", "GET", "/old")));
        IllegalStateException expected = new IllegalStateException("syntax failed");

        assertThatThrownBy(() -> service.reload(snapshot("repo", SHA_TWO.value()), () -> {
            throw expected;
        })).isSameAs(expected);
        assertThat(refs(service.lookupMatches("/old", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::analyzedRevision)
                .isEqualTo(SHA_ONE.value());
    }

    @Test
    void should_omit_nonterminal_rest_route_when_repo_has_valid_sibling(CapturedOutput output) {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo", SHA_ONE.value()), syntax(
                route("InvalidControllerSentinel", "invalidSentinel", "GET", "/files/{*path}/metadata-sentinel"),
                route("ValidController", "valid", "GET", "/files/valid")));

        assertThat(refs(service.lookupMatches("/files/a/metadata-sentinel", "GET", ""))).isEmpty();
        assertThat(refs(service.suggestMatches("/files/a/metadata-sentinel", "GET", "", 10)))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("valid");
        assertThat(refs(service.lookupMatches("/files/valid", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("valid");
        assertThat(output)
                .contains("repoId=repo", "category=NONTERMINAL_REST_WILDCARD")
                .doesNotContain(
                        "/files/{*path}/metadata-sentinel",
                        "InvalidControllerSentinel",
                        "invalidSentinel",
                        "com.example");
    }

    @Test
    void should_return_other_repo_without_stale_repo_when_one_repo_write_lock_and_rebuild_are_blocked()
            throws Exception {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-a", SHA_ONE.value()), syntax(route("OldController", "old", "GET", "/shared")));
        service.reload(snapshot("repo-b", SHA_TWO.value()),
                syntax(route("OtherController", "other", "GET", "/shared")));
        ReentrantReadWriteLock repoALock = new ReentrantReadWriteLock(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> rebuild = CompletableFuture.runAsync(() -> {
            repoALock.writeLock().lock();
            try {
                service.reload(snapshot("repo-a", SHA_TWO.value()), () -> {
                    started.countDown();
                    ConcurrencyTestSupport.await(release, Duration.ofSeconds(5));
                    return syntax(route("NewController", "newRoute", "GET", "/shared"));
                });
            } finally {
                repoALock.writeLock().unlock();
            }
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repoALock.isWriteLocked()).isTrue();
        assertThat(refs(service.lookupMatches("/shared", "GET", "")))
                .extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-b");
        release.countDown();
        rebuild.get(5, TimeUnit.SECONDS);
        assertThat(refs(service.lookupMatches("/shared", "GET", "")))
                .extracting(ApiEntryPointRef::methodName)
                .containsExactly("newRoute", "other");
    }

    @Test
    void should_hide_partial_repo_when_rebuild_is_blocked_during_state_construction() throws Exception {
        ApiTrieService service = new ApiTrieService();
        service.reload(snapshot("repo-a", SHA_ONE.value()),
                syntax(route("OldController", "old", "GET", "/publication")));
        service.reload(snapshot("repo-b", SHA_TWO.value()),
                syntax(route("OtherController", "other", "GET", "/publication")));
        CountDownLatch supplierReturned = new CountDownLatch(1);
        CountDownLatch constructionBlocked = new CountDownLatch(1);
        CountDownLatch releaseConstruction = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(ApiTrieService.class);
        BlockingCollisionAppender appender = new BlockingCollisionAppender(
                "category=CROSS_REPOSITORY_COLLISION", constructionBlocked, releaseConstruction);
        appender.start();
        try {
            logger.addAppender(appender);
            CompletableFuture<Void> rebuild = CompletableFuture.runAsync(() -> service.reload(
                    snapshot("repo-a", SHA_TWO.value()),
                    () -> {
                        supplierReturned.countDown();
                        return syntax(route("NewController", "newRoute", "GET", "/publication"));
                    }));
            try {
                assertThat(supplierReturned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(constructionBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(refs(service.lookupMatches("/publication", "GET", "")))
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
        assertThat(refs(service.lookupMatches("/publication", "GET", "")))
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::methodName)
                .containsExactly(
                        Tuple.tuple("repo-a", "newRoute"),
                        Tuple.tuple("repo-b", "other"));
    }

    @Test
    void should_preserve_the_exact_api_entry_point_resolution_in_the_trie_reference() {
        MethodTargetResolution resolution = MethodTargetResolution.resolved(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Controller"),
                        "src/main/java/com/example/Controller.java"),
                "handler",
                List.of()));
        EntryPointClass entryPointClass = route("Controller", "handler", "GET", "/route", resolution);
        ApiTrieService service = new ApiTrieService();

        service.reload(snapshot("repo", SHA_ONE.value()), syntax(entryPointClass));

        assertThat(service.lookupMatches("/route", "GET", "").matches().get(0).ref().analysisTarget())
                .isSameAs(resolution);
    }

    private static RepositorySnapshot snapshot(String repoId, String revision) {
        return new RepositorySnapshot(
                RepositoryId.of(repoId),
                Path.of(".").toAbsolutePath().normalize(),
                new RepositoryRevision(revision));
    }

    private static List<ApiEntryPointRef> refs(ApiRouteMatchBatch batch) {
        return batch.matches().stream().map(ApiRouteMatch::ref).toList();
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
            String className,
            String methodName,
            String httpMethod,
            String path,
            MethodTargetResolution resolution) {
        List<EntryPointMethod> methods = List.of(new ApiEntryPoint(
                methodName, "", path, List.of(httpMethod), List.of(), resolution));
        return new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        "com/example/" + className + ".java"),
                "",
                List.of(),
                methods);
    }

    private static EntryPointClass route(
            String packageName,
            String className,
            String methodName,
            String httpMethod,
            String path) {
        return route(packageName, className, methodName, httpMethod, path, resolvedTarget(
                packageName, className, methodName));
    }

    private static EntryPointClass route(
            String packageName,
            String className,
            String methodName,
            String httpMethod,
            String path,
            MethodTargetResolution resolution) {
        List<EntryPointMethod> methods = List.of(new ApiEntryPoint(
                methodName, "", path, List.of(httpMethod), List.of(), resolution));
        return new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(packageName, className),
                        packageName.replace('.', '/') + "/" + className + ".java"),
                "",
                List.of(),
                methods);
    }

    private static MethodTargetResolution resolvedTarget(String packageName, String className, String methodName) {
        return MethodTargetResolution.resolved(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(packageName, className),
                        "src/main/java/" + packageName.replace('.', '/') + "/" + className + ".java"),
                methodName,
                List.of()));
    }

    private static final class BlockingCollisionAppender extends AppenderBase<ILoggingEvent> {

        private final String trigger;
        private final CountDownLatch blocked;
        private final CountDownLatch release;

        private BlockingCollisionAppender(
                String trigger,
                CountDownLatch blocked,
                CountDownLatch release) {
            this.trigger = trigger;
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().contains(trigger)) {
                blocked.countDown();
                ConcurrencyTestSupport.await(release, Duration.ofSeconds(5));
            }
        }
    }
}
