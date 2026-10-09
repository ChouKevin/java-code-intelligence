package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SourceCapacityAdmissionTest {
    @TempDir Path root;

    @Test
    void capacity_rejection_is_durable_and_preserves_ready_source_without_fetching_the_new_commit() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String firstRevision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = new RepositoryProperties();
            properties.setSourceAdminRoot(fixture.admin.toString());
            properties.setSourcePublishedRoot(fixture.published.toString());
            RepositoryProperties.RepositoryConfig repository = new RepositoryProperties.RepositoryConfig();
            repository.setUrl(fixture.remote.toUri().toString());
            repository.setDefaultBranch("main");
            repository.setDisplayName("Orders");
            properties.setRepositories(Map.of(fixture.repository.value(), repository));
            ObjectMapper mapper = JsonMapper.builder().build();
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
                SourcePublicationStore publications = SourceLifecycleFixture.publications(properties, mapper, owner, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, publications, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                assertThat(manager.execute(jobs.claimNext().orElseThrow()).phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                String secondRevision = fixture.commit("A.java", "class A { int changed; }\n".getBytes(StandardCharsets.UTF_8), "B");
                new Binder(new MapConfigurationPropertySource(Map.of("semantic.source-capacity.max-managed-bytes", "1")))
                        .bind("semantic", Bindable.ofInstance(properties));
                PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
                service.prepareSource(fixture.repository, request, Optional.of(RepositoryRevision.ofSha(secondRevision)));
                SourcePreparationJob rejected = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(rejected.phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
                assertThat(rejected.failureCode()).contains("CAPACITY_EXCEEDED");
                assertThat(new FileSourceJobStore(properties, mapper, owner)
                        .find(fixture.repository, new IndexJobId(rejected.jobId())).orElseThrow()).isEqualTo(rejected);
                assertThat(publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(firstRevision);
                assertThat(publications.state(fixture.repository).published()).containsKey(firstRevision).doesNotContainKey(secondRevision);
                assertThat(Files.readString(fixture.published.resolve("orders/revisions").resolve(firstRevision)
                        .resolve("tree/A.java"))).isEqualTo("class A {}\n");
                try (Git cached = resolver.open(fixture.repository)) {
                    assertThat(cached.getRepository().getObjectDatabase().has(ObjectId.fromString(secondRevision)))
                            .isFalse();
                }
            }
        }
    }

    @Test
    void fetched_objects_crossing_capacity_refuse_export_and_preserve_the_ready_publication() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String firstRevision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(firstRevision);
            byte[] publishedBytes = Files.readAllBytes(preparation.tree(firstRevision).resolve("A.java"));
            Map<String, PreparedRevision> membership =
                    preparation.publications.state(fixture.repository).published();
            byte[] largeBlob = new byte[1024 * 1024];
            new Random(17).nextBytes(largeBlob);
            String secondRevision = fixture.commit("payload.bin", largeBlob, "large fetched object");
            PreparationRequestId request = preparation.admit(secondRevision);
            SourcePreparationJob claimed = preparation.jobs.claimNext().orElseThrow();
            long maximum = managedBytes(fixture) + 64 * 1024;
            preparation.capacity("max-managed-bytes", maximum);

            SourcePreparationJob rejected = preparation.manager.execute(claimed);

            preparation.assertDurableRefusal(request, rejected);
            assertThat(rejected.resolvedRevision()).contains(secondRevision);
            try (Git cached = preparation.resolver.open(fixture.repository)) {
                assertThat(cached.getRepository().getObjectDatabase().has(ObjectId.fromString(secondRevision))).isTrue();
            }
            assertThat(managedBytes(fixture)).isGreaterThan(maximum);
            assertThat(fixture.admin.resolve("staging").resolve(rejected.jobId())).doesNotExist();
            assertThat(preparation.publications.state(fixture.repository).current().orElseThrow().revision())
                    .isEqualTo(firstRevision);
            assertThat(preparation.publications.state(fixture.repository).published()).isEqualTo(membership);
            assertThat(Files.readAllBytes(preparation.tree(firstRevision).resolve("A.java"))).isEqualTo(publishedBytes);
            assertThat(preparation.tree(secondRevision).getParent()).doesNotExist();
        }
    }

    @Test
    void exported_staging_crossing_capacity_is_refused_before_seal_without_changing_ready_bytes_or_membership()
            throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String firstRevision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(firstRevision);
            byte[] publishedBytes = Files.readAllBytes(preparation.tree(firstRevision).resolve("A.java"));
            Map<String, PreparedRevision> membership =
                    preparation.publications.state(fixture.repository).published();
            byte[] largeText = new byte[1024 * 1024];
            Arrays.fill(largeText, (byte) 'a');
            String secondRevision = fixture.commit("Large.java", largeText, "large exported tree");
            // Pre-fetch the exact revision so only exported logical bytes can consume the admission headroom.
            preparation.resolver.resolve(fixture.repository, Optional.of(RepositoryRevision.ofSha(secondRevision)));
            PreparationRequestId request = preparation.admit(secondRevision);
            SourcePreparationJob claimed = preparation.jobs.claimNext().orElseThrow();
            long maximum = managedBytes(fixture) + 64 * 1024;
            preparation.capacity("max-managed-bytes", maximum);

            SourcePreparationJob rejected = preparation.manager.execute(claimed);

            preparation.assertDurableRefusal(request, rejected);
            assertThat(rejected.resolvedRevision()).contains(secondRevision);
            Path staging = fixture.admin.resolve("staging").resolve(rejected.jobId());
            assertThat(Files.size(staging.resolve("tree/Large.java"))).isEqualTo((long) largeText.length);
            assertThat(staging.resolve("inventory.jsonl")).isRegularFile();
            assertThat(staging.resolve("manifest.json")).doesNotExist();
            assertThat(managedBytes(fixture)).isGreaterThan(maximum);
            assertThat(preparation.tree(secondRevision).getParent()).doesNotExist();
            assertThat(preparation.publications.state(fixture.repository).current().orElseThrow().revision())
                    .isEqualTo(firstRevision);
            assertThat(preparation.publications.state(fixture.repository).published()).isEqualTo(membership);
            assertThat(Files.readAllBytes(preparation.tree(firstRevision).resolve("A.java"))).isEqualTo(publishedBytes);
        }
    }

    @Test
    void exact_free_space_boundary_allows_publication_but_one_byte_less_refuses_it() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String revision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(revision);
            Optional<CurrentPublication> beforeCurrent =
                    preparation.publications.state(fixture.repository).current();
            preparation.capacity("min-free-bytes", 65536);
            FileStore space = mock(FileStore.class);
            when(space.getUsableSpace()).thenReturn(65536L);
            try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
                files.when(() -> Files.getFileStore(fixture.admin)).thenReturn(space);
                files.when(() -> Files.getFileStore(fixture.published)).thenReturn(space);
                PreparationRequestId accepted = preparation.admit(revision);
                SourcePreparationJob complete = preparation.manager.execute(preparation.jobs.claimNext().orElseThrow());
                assertThat(complete.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(preparation.service.getJob(fixture.repository, Optional.empty(), Optional.of(accepted)))
                        .isEqualTo(complete);
                when(space.getUsableSpace()).thenReturn(65535L);
                PreparationRequestId refused = preparation.admit(revision);
                SourcePreparationJob rejected = preparation.manager.execute(preparation.jobs.claimNext().orElseThrow());
                preparation.assertDurableRefusal(refused, rejected);
                assertThat(preparation.publications.state(fixture.repository).current()).isEqualTo(beforeCurrent);
                assertThat(Files.readString(preparation.tree(revision).resolve("A.java"))).isEqualTo("class A {}\n");
            }
        }
    }

    @Test
    void unsafe_capacity_measurement_is_a_durable_failure_without_changing_ready_source() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String revision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(revision);
            Map<String, PreparedRevision> membership = preparation.publications.state(fixture.repository).published();
            Files.createSymbolicLink(fixture.admin.resolve("unsafe-capacity-entry"), fixture.remote);
            preparation.capacity("max-managed-bytes", Long.MAX_VALUE);
            PreparationRequestId request = preparation.admit(revision);
            SourcePreparationJob rejected = preparation.manager.execute(preparation.jobs.claimNext().orElseThrow());
            assertThat(rejected.phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
            assertThat(rejected.failureCode()).contains("CAPACITY_UNAVAILABLE");
            FileSourceJobStore reopened = new FileSourceJobStore(preparation.properties, preparation.mapper, preparation.owner);
            assertThat(reopened.find(fixture.repository, request).orElseThrow()).isEqualTo(rejected);
            assertThat(preparation.service.getJob(fixture.repository, Optional.empty(), Optional.of(request))).isEqualTo(rejected);
            assertThat(preparation.publications.state(fixture.repository).published()).isEqualTo(membership);
            assertThat(preparation.publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revision);
            assertThat(Files.readString(preparation.tree(revision).resolve("A.java"))).isEqualTo("class A {}\n");
        }
    }

    @Test
    void committed_receipt_completes_running_job_despite_a_now_rejecting_capacity_policy() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String revision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(revision);
            PreparedRevision original = preparation.publications.state(fixture.repository).published().get(revision);
            byte[] manifestBytes = Files.readAllBytes(preparation.tree(revision).getParent().resolve("manifest.json"));
            PreparationRequestId request = preparation.admit(revision);
            SourcePreparationJob running = preparation.jobs.claimNext().orElseThrow();
            SourcePreparationJob pinned = preparation.jobs.recordResolved(fixture.repository, new IndexJobId(running.jobId()),
                    RepositoryRevision.ofSha(revision));
            PreparedRevision committed = preparation.publications.publish(pinned, preparation.publications.manifest(
                    new SourceContext(fixture.repository.value(), revision), original.manifestDigest()));
            assertThat(preparation.jobs.find(fixture.repository, request).orElseThrow().phase())
                    .isEqualTo(SourcePreparationJob.Phase.RUNNING);
            preparation.capacity("max-managed-bytes", 1);
            SourcePreparationJob recovered = preparation.manager.execute(pinned);
            assertThat(recovered.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(recovered.publication()).contains(committed);
            assertThat(recovered.failureCode()).isEmpty();
            assertThat(preparation.service.getJob(fixture.repository, Optional.empty(), Optional.of(request))).isEqualTo(recovered);
            assertThat(preparation.publications.state(fixture.repository).preparation().jobId())
                    .contains(pinned.jobId());
            assertThat(Files.readAllBytes(preparation.tree(revision).getParent().resolve("manifest.json"))).isEqualTo(manifestBytes);
            assertThat(Files.readString(preparation.tree(revision).resolve("A.java"))).isEqualTo("class A {}\n");
        }
    }

    @Test
    void increasing_free_space_headroom_allows_a_new_request_but_preserves_the_failed_original_intent() throws Exception {
        assertFreeSpaceRecovery(1);
    }

    @Test
    void disabling_free_space_admission_allows_a_new_request_but_preserves_the_failed_original_intent() throws Exception {
        assertFreeSpaceRecovery(0);
    }

    private void assertFreeSpaceRecovery(long relaxedMinimum) throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                PreparationFixture preparation = new PreparationFixture(fixture)) {
            String revision = fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            preparation.prepare(revision);
            byte[] publishedBytes = Files.readAllBytes(preparation.tree(revision).resolve("A.java"));
            Map<String, PreparedRevision> membership =
                    preparation.publications.state(fixture.repository).published();
            assertThat(Files.getFileStore(fixture.admin).getUsableSpace()).isLessThan(Long.MAX_VALUE);
            preparation.capacity("min-free-bytes", Long.MAX_VALUE);
            PreparationRequestId original = preparation.admit(revision);

            SourcePreparationJob rejected = preparation.manager.execute(preparation.jobs.claimNext().orElseThrow());

            preparation.assertDurableRefusal(original, rejected);
            assertThat(rejected.resolvedRevision()).isEmpty();
            assertThat(preparation.publications.state(fixture.repository).published()).isEqualTo(membership);
            assertThat(Files.readAllBytes(preparation.tree(revision).resolve("A.java"))).isEqualTo(publishedBytes);
            preparation.capacity("min-free-bytes", relaxedMinimum);
            assertThatThrownBy(() -> preparation.service.prepareSource(fixture.repository, original,
                    Optional.of(RepositoryRevision.ofSha(revision)))).isInstanceOf(PreparationRequestReusedException.class);
            assertThat(preparation.jobs.claimNext()).isEmpty();
            PreparationRequestId newRequest = preparation.admit(revision);
            SourcePreparationJob completed = preparation.manager.execute(preparation.jobs.claimNext().orElseThrow());
            assertThat(completed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(completed.jobId()).isNotEqualTo(rejected.jobId());
            assertThat(preparation.service.getJob(fixture.repository, Optional.empty(), Optional.of(newRequest)))
                    .isEqualTo(completed);
            preparation.assertDurableRefusal(original, rejected);
            assertThat(preparation.publications.state(fixture.repository).published()).isEqualTo(membership);
            assertThat(preparation.publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revision);
            assertThat(Files.readAllBytes(preparation.tree(revision).resolve("A.java"))).isEqualTo(publishedBytes);
        }
    }

    private static long managedBytes(LocalSourceFixture fixture) throws IOException {
        long[] total = {0};
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    total[0] = Math.addExact(total[0], attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(fixture.admin, visitor);
        Files.walkFileTree(fixture.published, visitor);
        return total[0];
    }

    private static final class PreparationFixture implements AutoCloseable {
        private final LocalSourceFixture source;
        private final RepositoryProperties properties = new RepositoryProperties();
        private final ObjectMapper mapper = JsonMapper.builder().build();
        private final DurableSourceFiles owner;
        private final FileSourceJobStore jobs;
        private final SourcePublicationStore publications;
        private final RepositoryRevisionResolver resolver;
        private final RepositorySourceManager manager;
        private final SourcePreparationService service;

        private PreparationFixture(LocalSourceFixture source) throws IOException {
            this.source = source;
            properties.setSourceAdminRoot(source.admin.toString());
            properties.setSourcePublishedRoot(source.published.toString());
            RepositoryProperties.RepositoryConfig repository = new RepositoryProperties.RepositoryConfig();
            repository.setUrl(source.remote.toUri().toString());
            repository.setDefaultBranch("main");
            repository.setDisplayName("Orders");
            properties.setRepositories(Map.of(source.repository.value(), repository));
            owner = new DurableSourceFiles(source.admin);
            RepositoryRegistry registry = new RepositoryRegistry(properties);
            jobs = new FileSourceJobStore(properties, mapper, owner);
            publications = SourceLifecycleFixture.publications(properties, mapper, owner, jobs);
            resolver = new RepositoryRevisionResolver(registry, properties);
            manager = new RepositorySourceManager(jobs, resolver, new JGitRevisionExporter(resolver, registry, mapper),
                    publications, registry, properties);
            service = new SourcePreparationService(jobs, publications, registry);
        }

        private PreparationRequestId admit(String revision) {
            PreparationRequestId request = new PreparationRequestId(UUID.randomUUID().toString());
            service.prepareSource(source.repository, request, Optional.of(RepositoryRevision.ofSha(revision)));
            return request;
        }

        private void prepare(String revision) {
            service.prepareSource(source.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
            SourcePreparationJob completed = manager.execute(jobs.claimNext().orElseThrow());
            assertThat(completed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(completed.resolvedRevision()).contains(revision);
        }

        private Path tree(String revision) {
            return source.published.resolve("orders/revisions").resolve(revision).resolve("tree");
        }

        private void capacity(String setting, long value) {
            new Binder(new MapConfigurationPropertySource(Map.of("semantic.source-capacity." + setting,
                    Long.toString(value)))).bind("semantic", Bindable.ofInstance(properties));
        }

        private void assertDurableRefusal(PreparationRequestId request, SourcePreparationJob rejected) {
            assertThat(rejected.phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
            assertThat(rejected.failureCode()).contains("CAPACITY_EXCEEDED");
            FileSourceJobStore reopened = new FileSourceJobStore(properties, mapper, owner);
            assertThat(reopened.find(source.repository, request).orElseThrow()).isEqualTo(rejected);
            SourcePreparationService recovered = new SourcePreparationService(reopened, publications,
                    new RepositoryRegistry(properties));
            assertThat(recovered.getJob(source.repository, Optional.empty(), Optional.of(request))).isEqualTo(rejected);
        }

        @Override
        public void close() throws IOException {
            owner.close();
        }
    }
}
