package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexRepositoryState;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.SourceOperationDeadline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class IndexReadLifecycleTest {
    @TempDir Path temporary;

    @Test void fold_reads_keep_original_deadline_and_borrowed_guard_through_sql_cleanup() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        AtomicBoolean released = new AtomicBoolean();
        try (AdmittedSourceRevision source = fixture.admit()) {
            source.lease().onRelease(() -> released.set(true));
            assertThatThrownBy(() -> SourceOperationDeadline.within(Duration.ofNanos(1),
                    () -> fixture.reader().folds(source, "Names.java", Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_TIMEOUT));
            assertThat(released).isFalse();
            assertThat(fixture.reader().folds(source, "Names.java", Optional.empty()).folds()).hasSize(1);
            for (String suffix : List.of("-wal", "-shm", "-journal"))
                assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite" + suffix))).isFalse();
            assertThat(released).isFalse();
        }
        assertThat(released).isTrue();
    }

    @Test void fold_continuation_uses_retained_exact_generation_and_never_retargets_after_expiry() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Path statePath = fixture.root.resolve("sample/state.json");
        IndexRepositoryState oldState = fixture.mapper.readValue(Files.readAllBytes(statePath), IndexRepositoryState.class);
        String old = fixture.manifest.indexGeneration();
        fixture.publish(List.of("A", "run"), 0);
        byte[] replacement = Files.readAllBytes(statePath);
        IndexRepositoryState newState = fixture.mapper.readValue(replacement, IndexRepositoryState.class);
        RevisionIndexes selected = newState.revisions().get(fixture.context.revision());
        Map<String, Receipt> retained = new HashMap<>(selected.generations());
        retained.putAll(oldState.revisions().get(fixture.context.revision()).generations());
        RevisionIndexes indexes = new RevisionIndexes(selected.sourceManifestDigest(), selected.selectedGeneration(), retained);
        Files.write(statePath, fixture.mapper.writeValueAsBytes(new IndexRepositoryState(
                newState.formatVersion(), newState.repositoryId(), newState.originFingerprint(), Map.of(fixture.context.revision(), indexes))));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThat(fixture.reader().folds(source, "Names.java", Optional.of(old)).folds()).hasSize(1);
            assertThat(fixture.reader().folds(source, "Names.java", Optional.empty()).indexGeneration()).isNotEqualTo(old);
            Files.write(statePath, replacement);
            assertThatThrownBy(() -> fixture.reader().folds(source, "Names.java", Optional.of(old)))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_GENERATION_EXPIRED));
        }
    }

    @Test void actual_sql_finishes_without_releasing_the_callers_source_guard_or_creating_sidecars() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        AtomicBoolean released = new AtomicBoolean();
        try (AdmittedSourceRevision source = fixture.admit()) {
            source.lease().onRelease(() -> released.set(true));
            assertThat(fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())).items()).hasSize(1);
            assertThat(released).isFalse();
            assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-wal"))).isFalse();
            assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-shm"))).isFalse();
            assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-journal"))).isFalse();
        }
        assertThat(released).isTrue();
    }

    @Test void expired_parent_budget_is_not_restarted_and_cleanup_keeps_the_borrowed_guard() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        AtomicBoolean released = new AtomicBoolean();
        try (AdmittedSourceRevision source = fixture.admit()) {
            source.lease().onRelease(() -> released.set(true));
            assertThatThrownBy(() -> SourceOperationDeadline.within(Duration.ofNanos(1),
                    () -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty()))))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_TIMEOUT));
            assertThat(released).isFalse();
            assertThat(fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())).items()).hasSize(1);
        }
        assertThat(released).isTrue();
    }

    @Test void selected_replacement_does_not_modify_the_live_old_immutable_database() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        String old = fixture.manifest.indexGeneration();
        try (AdmittedSourceRevision source = fixture.admit();
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database.toUri().toASCIIString() + "?mode=ro&immutable=1");
                Statement statement = connection.createStatement()) {
            fixture.publish(List.of("A"), 1);
            try (ResultSet identity = statement.executeQuery("SELECT generation FROM identity")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo(old);
            }
            assertThat(fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())).indexGeneration()).isNotEqualTo(old);
        }
    }

    @Test void interrupted_read_does_not_clear_cancellation_or_close_the_borrowed_guard() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        AtomicBoolean released = new AtomicBoolean();
        try (AdmittedSourceRevision source = fixture.admit()) {
            source.lease().onRelease(() -> released.set(true));
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())))
                        .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_TIMEOUT));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(released).isFalse();
            } finally { Thread.interrupted(); }
        }
        assertThat(released).isTrue();
    }
}
