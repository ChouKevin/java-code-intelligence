package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexRepositoryState;
import com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticIndexRecoveryTest {
    @TempDir Path root;

    @Test
    void committed_receipt_wins_when_terminal_write_is_lost_and_recovery_does_not_check_capacity() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            fixture.jobs.admit(input, request, Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, fixture.stage(input), () -> { });
            Receipt committed = fixture.indexes.publish(running, fixture.stage(input), manifest);
            IndexBuildInput nextSource = fixture.prepare("class A { int newer; }\n");
            assertThat(fixture.sources.state(fixture.git.repository).current().orElseThrow().revision())
                    .isEqualTo(nextSource.context().revision()).isNotEqualTo(input.context().revision());
            // Crash window: publication committed, private job remains RUNNING; no complete() call.
            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.indexes);
            SemanticIndexJob recovered = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(recovered.phase()).isEqualTo(SemanticIndexJob.Phase.COMPLETE);
            assertThat(recovered.publication()).contains(committed);
            assertThat(recovered.expectedSelection()).isEqualTo(running.expectedSelection());
            assertThat(recovered.sourceManifestDigest()).isEqualTo(running.sourceManifestDigest());
            assertThat(recovered.acceptedAt()).isEqualTo(running.acceptedAt());
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).contains(committed);
            assertThatThrownBy(() -> reopened.admit(fixture.input(input.context()), request, Optional.of(input.generation())))
                    .isInstanceOf(PreparationRequestReusedException.class);
        }
    }

    @Test
    void known_schema_one_committed_receipt_recovers_after_current_changes_but_cannot_be_newly_published() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            fixture.jobs.admit(input, request, Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Path stage = fixture.stage(input);
            IndexManifest built = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, stage, () -> { });
            IndexContract.Profile legacy = new IndexContract.Profile(1, 1, "JavaParser", "3.28.2", "JAVA_21", true, 1);
            Path database = stage.resolve("index.sqlite");
            // Model a genuine pre-upgrade schema: no fold table, and legacy identity in the database.
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    Statement statement = connection.createStatement();
                    PreparedStatement identity = connection.prepareStatement("UPDATE identity SET profile_json=?")) {
                statement.execute("DROP TABLE folds");
                identity.setString(1, fixture.mapper.writeValueAsString(legacy));
                identity.executeUpdate();
            }
            IndexManifest manifest = new IndexManifest(built.formatVersion(), built.context(), built.originFingerprint(),
                    built.indexGeneration(), built.sourceManifestDigest(), built.inventoryDigest(), legacy, built.coverage(),
                    Files.size(database), IndexFiles.digest(database), built.preparedAt());
            Files.write(stage.resolve("manifest.json"), fixture.mapper.writeValueAsBytes(manifest));
            assertThatThrownBy(() -> fixture.indexes.publish(running, stage, manifest))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_INCOMPATIBLE));
            // Install the already-committed pre-upgrade receipt, never ask the new producer to publish it.
            Receipt committed = new Receipt(manifest, IndexFiles.digest(stage.resolve("manifest.json")), running.jobId(), running.requestId());
            Path generation = fixture.generation(input);
            Files.createDirectories(generation.getParent());
            Files.move(stage, generation);
            IndexRepositoryState state = new IndexRepositoryState(IndexContract.FORMAT_VERSION, input.context().repositoryId(),
                    input.originFingerprint(), Map.of(input.context().revision(), new RevisionIndexes(input.sourceManifestDigest(),
                            Optional.of(input.generation()), Map.of(input.generation(), committed))));
            Files.write(Path.of(fixture.properties.getIndexPublishedRoot()).resolve("orders/state.json"), fixture.mapper.writeValueAsBytes(state));
            fixture.prepare("class A { int newer; }\n");
            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.indexes);
            SemanticIndexJob recovered = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(recovered.phase()).isEqualTo(SemanticIndexJob.Phase.COMPLETE);
            assertThat(recovered.publication()).contains(committed);
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).contains(committed);
        }
    }

    @Test
    void accepted_historical_intent_is_failed_with_original_identity_before_dispatch() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.jobs.admit(input, request, Optional.empty());
            fixture.prepare("class A { int newer; }\n");
            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.indexes);
            SemanticIndexJob failed = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(failed.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(failed.failureCode()).contains("INDEX_NOT_CURRENT");
            assertThat(failed.jobId()).isEqualTo(accepted.jobId());
            assertThat(failed.indexGeneration()).isEqualTo(accepted.indexGeneration());
            assertThat(failed.acceptedAt()).isEqualTo(accepted.acceptedAt());
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
            assertThat(Files.exists(fixture.stage(input))).isFalse();
        }
    }

    @Test
    void accepted_inventory_binding_change_fails_original_intent_before_dispatch() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.jobs.admit(input, request, Optional.empty());
            ObjectNode originalIntent = fixture.mapper.valueToTree(accepted);
            originalIntent.put("inventoryDigest", "0".repeat(64));
            Files.write(fixture.git.admin.resolve("index/jobs/orders").resolve(request.value() + ".json"),
                    fixture.mapper.writeValueAsBytes(originalIntent));
            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.indexes);
            SemanticIndexJob failed = reopened.find(fixture.git.repository, request).orElseThrow();
            assertThat(failed.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(failed.failureCode()).contains("INDEX_NOT_CURRENT");
            assertThat(failed.jobId()).isEqualTo(accepted.jobId());
            assertThat(failed.requestId()).isEqualTo(accepted.requestId());
            assertThat(failed.indexGeneration()).isEqualTo(accepted.indexGeneration());
            assertThat(failed.inventoryDigest()).isEqualTo("0".repeat(64));
            assertThat(fixture.sources.state(fixture.git.repository).current().orElseThrow().revision()).isEqualTo(input.context().revision());
            assertThat(reopened.claimNext()).isEmpty();
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
            assertThat(Files.exists(fixture.stage(input))).isFalse();
        }
    }

    @Test
    void new_publication_rechecks_current_after_build_without_rejecting_historical_reads() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.jobs.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse)
                    .build(input, fixture.stage(input), () -> { });
            fixture.prepare("class A { int newer; }\n");
            assertThatThrownBy(() -> fixture.indexes.publish(running, fixture.stage(input), manifest))
                    .isInstanceOfSatisfying(IndexFailure.class, failure ->
                            assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_NOT_CURRENT));
            assertThat(fixture.indexes.receipt(running)).isEmpty();
            assertThat(Files.exists(fixture.generation(input))).isFalse();
            assertThat(fixture.sources.manifest(input.context(), input.sourceManifestDigest())).isEqualTo(input.sourceManifest());
        }
    }


    @Test
    void accepted_intent_survives_reopen_but_uncommitted_running_is_interrupted() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            SemanticIndexJob accepted = fixture.jobs.admit(input, request, Optional.empty());
            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            reopened.recover(fixture.indexes);
            assertThat(reopened.find(fixture.git.repository, request)).contains(accepted);
            SemanticIndexJob running = reopened.claimNext().orElseThrow();
            assertThat(running.jobId()).isEqualTo(accepted.jobId());
            // Even a fully built directory has no authority without membership+receipt.
            new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, fixture.stage(input), () -> { });
            FileSemanticIndexJobStore restarted = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
            restarted.recover(fixture.indexes);
            SemanticIndexJob interrupted = restarted.find(fixture.git.repository, new IndexJobId(accepted.jobId())).orElseThrow();
            assertThat(interrupted.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(interrupted.failureCode()).contains("WORKER_INTERRUPTED");
            assertThat(interrupted.indexGeneration()).isEqualTo(accepted.indexGeneration());
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
            assertThat(restarted.hasActive(fixture.git.repository)).isFalse();
        }
    }

    @Test
    void sealed_but_uncommitted_generation_is_not_adopted_and_cannot_be_republished() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.jobs.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, fixture.stage(input), () -> { });
            // Materialize the crash window after atomic directory move, before authority replacement.
            Files.createDirectories(fixture.generation(input).getParent());
            Files.move(fixture.stage(input), fixture.generation(input), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            assertThat(Files.exists(fixture.generation(input))).isTrue();
            assertThat(fixture.indexes.receipt(running)).isEmpty();
            assertThatThrownBy(() -> fixture.indexes.publish(running, fixture.stage(input), manifest)).isInstanceOf(IndexFailure.class);
            fixture.jobs.recover(fixture.indexes);
            assertThat(fixture.jobs.find(fixture.git.repository, new IndexJobId(running.jobId())).orElseThrow().failureCode())
                    .contains("WORKER_INTERRUPTED");
        }
    }

    @Test
    void malformed_committed_job_record_fails_closed_during_recovery() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            fixture.jobs.admit(input, request, Optional.empty());
            Path committed = fixture.git.admin.resolve("index/jobs/orders").resolve(request.value() + ".json");
            String malformed = "{\"formatVersion\":1,\"jobId\":";
            Files.writeString(committed, malformed);

            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(
                    fixture.properties, fixture.mapper, fixture.owner);
            assertThatThrownBy(() -> reopened.recover(fixture.indexes)).isInstanceOf(IndexFailure.class);
            assertThat(Files.readString(committed)).isEqualTo(malformed);
        }
    }

    @Test
    void unexpected_non_temporary_entry_fails_closed_during_recovery() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.jobs.admit(input, IndexArtifactFixture.request(), Optional.empty());
            Path unexpected = fixture.git.admin.resolve("index/jobs/orders/operator-note.tmp");
            Files.writeString(unexpected, "not a semantic job");

            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(
                    fixture.properties, fixture.mapper, fixture.owner);
            assertThatThrownBy(() -> reopened.recover(fixture.indexes)).isInstanceOf(IndexFailure.class);
            assertThat(Files.exists(unexpected)).isTrue();
        }
    }

    @Test
    void temporary_named_symlink_fails_closed_instead_of_being_ignored() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.jobs.admit(input, IndexArtifactFixture.request(), Optional.empty());
            Path directory = fixture.git.admin.resolve("index/jobs/orders");
            Path temporary = Files.createTempFile(directory, ".source-", ".tmp");
            Files.delete(temporary);
            Path target = Files.writeString(root.resolve("outside"), "not a job");
            Files.createSymbolicLink(temporary, target);

            FileSemanticIndexJobStore reopened = new FileSemanticIndexJobStore(
                    fixture.properties, fixture.mapper, fixture.owner);
            assertThatThrownBy(() -> reopened.recover(fixture.indexes)).isInstanceOf(IndexFailure.class);
            assertThat(Files.isSymbolicLink(temporary)).isTrue();
        }
    }

    @Test
    void duplicate_private_job_identity_fails_closed_before_recovery_rewrites_any_job() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexJob accepted = fixture.jobs.admit(input, IndexArtifactFixture.request(), Optional.empty());
            Path original = fixture.git.admin.resolve("index/jobs/orders").resolve(accepted.requestId() + ".json");
            byte[] before = Files.readAllBytes(original);
            Files.write(original.resolveSibling(IndexArtifactFixture.request().value() + ".json"), before);
            assertThatThrownBy(() -> fixture.jobs.recover(fixture.indexes)).isInstanceOf(IndexFailure.class);
            assertThat(Files.readAllBytes(original)).isEqualTo(before);
        }
    }

    @Test
    void corruption_of_a_committed_generation_is_not_downgraded_to_interrupted_work() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            PreparationRequestId request = IndexArtifactFixture.request();
            fixture.jobs.admit(input, request, Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, fixture.stage(input), () -> { });
            fixture.indexes.publish(running, fixture.stage(input), manifest);
            Files.writeString(fixture.generation(input).resolve("index.sqlite"), "corrupt");
            assertThatThrownBy(() -> fixture.jobs.recover(fixture.indexes)).isInstanceOf(IndexFailure.class);
            assertThat(fixture.jobs.find(fixture.git.repository, request).orElseThrow().phase()).isEqualTo(SemanticIndexJob.Phase.RUNNING);
        }
    }
}
