package com.java.semantic.indexer.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ApprovedOriginBindingTest {
    @TempDir Path root;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void cached_A_commit_cannot_be_adopted_or_published_after_restart_with_B() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revisionA = fixture.commit("A.java", "source A\n".getBytes(StandardCharsets.UTF_8), "A");
            Path other = unrelatedRemote();
            RepositoryProperties approvedA = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(approvedA);
                new ConfiguredRepositoryPublisher(registry, approvedA, mapper, owner).publish();
                assertThat(new RepositoryRevisionResolver(registry, approvedA).resolve(fixture.repository,
                        Optional.of(RepositoryRevision.ofSha(revisionA))).value()).isEqualTo(revisionA);
            }
            byte[] registryA = Files.readAllBytes(fixture.published.resolve("repositories.json"));
            RepositoryProperties approvedB = forRemote(fixture, other);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(approvedB), approvedB, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Files.readAllBytes(fixture.published.resolve("repositories.json"))).isEqualTo(registryA);
                assertThat(Files.exists(fixture.published.resolve("orders/state.json"))).isFalse();
                RepositoryProperties freshPublicB = forRemote(fixture, other);
                freshPublicB.setSourcePublishedRoot(root.resolve("fresh-public").toString());
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(freshPublicB), freshPublicB, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Files.exists(root.resolve("fresh-public/orders"))).isFalse();
            }
        }
    }

    @Test
    void accepted_A_branch_request_cannot_be_reinterpreted_against_B_after_restart() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "source A\n".getBytes(StandardCharsets.UTF_8), "A");
            Path other = unrelatedRemote();
            RepositoryProperties approvedA = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            String acceptedId;
            byte[] originalJob;
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(approvedA);
                new ConfiguredRepositoryPublisher(registry, approvedA, mapper, owner).publish();
                FileSourceJobStore jobs = new FileSourceJobStore(approvedA, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(approvedA, mapper, owner, jobs);
                acceptedId = new SourcePreparationService(jobs, publications, registry)
                        .prepareSource(fixture.repository, request, Optional.empty()).jobId();
                originalJob = Files.readAllBytes(fixture.admin.resolve("jobs/orders").resolve(request.value() + ".json"));
            }
            RepositoryProperties approvedB = forRemote(fixture, other);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(approvedB), approvedB, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
                FileSourceJobStore persisted = new FileSourceJobStore(approvedB, mapper, owner);
                SourcePreparationJob unchanged = persisted.find(fixture.repository, request).orElseThrow();
                assertThat(unchanged.jobId()).isEqualTo(acceptedId);
                assertThat(unchanged.phase()).isEqualTo(SourcePreparationJob.Phase.ACCEPTED);
                RepositoryRegistry changed = new RepositoryRegistry(approvedB);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(changed, approvedB);
                SourcePublicationStore publications = new SourcePublicationStore(approvedB, mapper, owner, persisted);
                RepositorySourceManager manager = new RepositorySourceManager(persisted, resolver,
                        new JGitRevisionExporter(resolver, changed, mapper), publications, changed, approvedB);
                assertThatThrownBy(() -> manager.execute(unchanged)).isInstanceOf(IllegalStateException.class);
                assertThat(Files.readAllBytes(fixture.admin.resolve("jobs/orders").resolve(request.value() + ".json")))
                        .isEqualTo(originalJob);
                assertThat(Files.exists(fixture.published.resolve("orders/revisions"))).isFalse();
            }
        }
    }

    @Test
    void published_A_is_immutable_when_B_reuses_its_public_namespace_with_fresh_admin() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            byte[] original = "source A\r\n".getBytes(StandardCharsets.UTF_8);
            String revisionA = fixture.commit("A.java", original, "A");
            Path other = unrelatedRemote();
            RepositoryProperties approvedA = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(approvedA);
                new ConfiguredRepositoryPublisher(registry, approvedA, mapper, owner).publish();
                FileSourceJobStore jobs = new FileSourceJobStore(approvedA, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(approvedA, mapper, owner, jobs);
                new SourcePreparationService(jobs, publications, registry).prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, approvedA);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, approvedA);
                assertThat(manager.execute(jobs.claimNext().orElseThrow()).phase())
                        .isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            }
            Path source = fixture.published.resolve("orders/revisions").resolve(revisionA).resolve("tree/A.java");
            byte[] stateA = Files.readAllBytes(fixture.published.resolve("orders/state.json"));
            byte[] registryA = Files.readAllBytes(fixture.published.resolve("repositories.json"));
            RepositoryProperties approvedB = forRemote(fixture, other);
            approvedB.setSourceAdminRoot(root.resolve("fresh-admin").toString());
            try (DurableSourceFiles owner = new DurableSourceFiles(root.resolve("fresh-admin"))) {
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(approvedB), approvedB, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(Files.readAllBytes(source)).isEqualTo(original);
            assertThat(Files.readAllBytes(fixture.published.resolve("orders/state.json"))).isEqualTo(stateA);
            assertThat(Files.readAllBytes(fixture.published.resolve("repositories.json"))).isEqualTo(registryA);
        }
    }

    @Test
    void nonempty_unbound_public_namespace_is_not_adopted_on_startup() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            Files.createDirectories(fixture.published.resolve("orders"));
            Files.writeString(fixture.published.resolve("orders/state.json"), "unbound-source-evidence");
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(properties), properties, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Files.readString(fixture.published.resolve("orders/state.json")))
                        .isEqualTo("unbound-source-evidence");
            }
        }
    }

    @Test
    void nonempty_unbound_private_object_namespace_is_not_adopted() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            Path objectStore = fixture.admin.resolve("repositories/orders/repository.git");
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                Files.createDirectories(objectStore);
                Files.writeString(objectStore.resolve("config"), "unbound-object-evidence");
                assertThatThrownBy(() -> new ConfiguredRepositoryPublisher(
                        new RepositoryRegistry(properties), properties, mapper, owner).publish())
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Files.readString(objectStore.resolve("config"))).isEqualTo("unbound-object-evidence");
            }
        }
    }

    @Test
    void same_origin_restart_preserves_accepted_job_and_can_complete_preparation() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revision = fixture.commit("A.java", "source A\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            String jobId;
            String fingerprint;
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                new ConfiguredRepositoryPublisher(registry, properties, mapper, owner).publish();
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                SourcePreparationJob job = new SourcePreparationService(jobs, publications, registry)
                        .prepareSource(fixture.repository, request, Optional.empty());
                jobId = job.jobId();
                fingerprint = job.originFingerprint();
            }
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                new ConfiguredRepositoryPublisher(registry, properties, mapper, owner).publish();
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs);
                jobs.recover(publications);
                SourcePreparationJob accepted = jobs.claimNext().orElseThrow();
                assertThat(accepted.jobId()).isEqualTo(jobId);
                assertThat(accepted.originFingerprint()).isEqualTo(fingerprint);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                SourcePreparationJob completed = manager.execute(accepted);
                assertThat(completed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(completed.originFingerprint()).isEqualTo(fingerprint);
                assertThat(publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revision);
            }
        }
    }

    @Test
    void malformed_endpoint_errors_never_include_credentials() {
        String secret = "origin-credential-sentinel";
        Throwable failure = catchThrowable(() ->
                ApprovedOriginBinding.fingerprint("https://user:" + secret + "%zz@example.invalid/repo.git"));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        StringWriter diagnostics = new StringWriter();
        failure.printStackTrace(new PrintWriter(diagnostics));
        assertThat(diagnostics.toString()).doesNotContain(secret);
    }

    @Test
    void malformed_http_authority_cannot_hide_credentials() {
        assertThatThrownBy(() -> ApprovedOriginBinding.fingerprint(
                "https://user:origin-credential-sentinel@example.invalid:bad/repo.git"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void http_user_info_is_not_an_origin_identity() {
        for (String scheme : List.of("http", "https")) {
            assertThatThrownBy(() -> ApprovedOriginBinding.fingerprint(
                    scheme + "://origin-credential-sentinel@example.invalid/repo.git"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ssh_usernames_remain_distinct_origin_identities() {
        assertThat(ApprovedOriginBinding.fingerprint("ssh://alice@example.invalid/repo.git"))
                .isNotEqualTo(ApprovedOriginBinding.fingerprint("ssh://bob@example.invalid/repo.git"));
        assertThat(ApprovedOriginBinding.fingerprint("alice@example.invalid:repo.git"))
                .isNotEqualTo(ApprovedOriginBinding.fingerprint("bob@example.invalid:repo.git"));
    }

    private Path unrelatedRemote() throws Exception {
        Path remote = root.resolve("other-remote");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("B.java"), "source B\n");
            git.add().addFilepattern("B.java").call();
            git.commit().setAuthor("Source fixture", "fixture@example.test").setMessage("B").call();
        }
        return remote;
    }

    private static RepositoryProperties forRemote(LocalSourceFixture fixture, Path remote) {
        RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
        properties.getRepositories().get("orders").setUrl(remote.toUri().toString());
        return properties;
    }
}
