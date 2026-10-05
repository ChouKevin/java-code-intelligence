package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideFreshness;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGuideSourceTest {
    @TempDir Path root;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void missing_and_plain_guide_never_gate_readiness_or_claim_verified_facts() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("Source.java", "class Source {}\n".getBytes(StandardCharsets.UTF_8), "without guide");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), store, registry, properties);
                SourcePreparationService service = new SourcePreparationService(jobs, store, registry);
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob first = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(first.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(store.manifest(first.publication().orElseThrow().context(),
                        first.publication().orElseThrow().manifestDigest()).projectGuide().state()).isEqualTo(GuideState.MISSING);
                fixture.commit("GUIDE.md", "Unverified hint\r\n".getBytes(StandardCharsets.UTF_8), "plain guide");
                service.prepareSource(fixture.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob second = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(second.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                SourceRevisionManifest manifest = store.manifest(second.publication().orElseThrow().context(),
                        second.publication().orElseThrow().manifestDigest());
                assertThat(manifest.projectGuide().state()).isEqualTo(GuideState.AVAILABLE);
                assertThat(manifest.projectGuide().freshness()).isEqualTo(GuideFreshness.NOT_VERIFIED);
                assertThat(Files.readAllBytes(fixture.published.resolve("orders/revisions")
                        .resolve(second.resolvedRevision().orElseThrow()).resolve("tree/GUIDE.md")))
                        .isEqualTo("Unverified hint\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void binary_invalid_encoding_lfs_pointer_and_excluded_tree_do_not_publish_readable_source() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String original = fixture.commit("ok.java", "class Ok {}\n".getBytes(StandardCharsets.UTF_8), "text");
            fixture.commit("binary.dat", new byte[] {1, 0, 2}, "binary");
            fixture.commit("invalid.txt", new byte[] {(byte) 0xc3, 0x28}, "bad utf8");
            fixture.commit("pointer.txt", ("version https://git-lfs.github.com/spec/v1\n"
                    + "oid sha256:" + "a".repeat(64) + "\nsize 1\n").getBytes(StandardCharsets.US_ASCII), "lfs");
            fixture.commit("target/hidden.java", "hidden".getBytes(StandardCharsets.UTF_8), "excluded");
            fixture.commit("target/nested/also-hidden.java",
                    "also hidden".getBytes(StandardCharsets.UTF_8), "second excluded file");
            Path outside = Files.writeString(root.resolve("outside-target.java"), "not exported");
            Files.createSymbolicLink(fixture.remote.resolve("outside.java"), outside);
            fixture.git.add().addFilepattern("outside.java").call();
            fixture.git.commit().setAuthor("Source fixture", "fixture@example.test").setMessage("link").call();
            org.eclipse.jgit.dircache.DirCache cache = fixture.git.getRepository().lockDirCache();
            try {
                org.eclipse.jgit.dircache.DirCacheEditor editor = cache.editor();
                editor.add(new org.eclipse.jgit.dircache.DirCacheEditor.PathEdit("module") {
                    @Override
                    public void apply(org.eclipse.jgit.dircache.DirCacheEntry entry) {
                        entry.setFileMode(org.eclipse.jgit.lib.FileMode.GITLINK);
                        entry.setObjectId(org.eclipse.jgit.lib.ObjectId.fromString(original));
                    }
                });
                editor.commit();
            } finally {
                cache.unlock();
            }
            fixture.git.commit().setAuthor("Source fixture", "fixture@example.test").setMessage("gitlink").call();
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), store, registry, properties);
                new SourcePreparationService(jobs, store, registry).prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob complete = manager.execute(jobs.claimNext().orElseThrow());
                SourceRevisionManifest manifest = store.manifest(complete.publication().orElseThrow().context(),
                        complete.publication().orElseThrow().manifestDigest());
                assertThat(manifest.coverage().unsupportedCounts()).containsEntry(EntryStatus.BINARY, 1L)
                        .containsEntry(EntryStatus.UNSUPPORTED_ENCODING, 1L)
                        .containsEntry(EntryStatus.LFS_POINTER, 1L)
                        .containsEntry(EntryStatus.SYMLINK, 1L)
                        .containsEntry(EntryStatus.SUBMODULE, 1L);
                assertThat(manifest.coverage().excludedFileCount()).isEqualTo(2);
                Path tree = fixture.published.resolve("orders/revisions").resolve(complete.resolvedRevision().orElseThrow())
                        .resolve("tree");
                assertThat(Files.exists(tree.resolve("ok.java"))).isTrue();
                assertThat(Files.exists(tree.resolve("target/hidden.java"))).isFalse();
                assertThat(Files.exists(tree.resolve("target/nested/also-hidden.java"))).isFalse();
                assertThat(Files.exists(tree.resolve("binary.dat"))).isFalse();
                assertThat(Files.exists(tree.resolve("invalid.txt"))).isFalse();
                assertThat(Files.exists(tree.resolve("pointer.txt"))).isFalse();
                assertThat(Files.exists(tree.resolve("outside.java"))).isFalse();
                assertThat(Files.exists(tree.resolve("module"))).isFalse();
            }
        }
    }
    @Test
    void unsafe_guide_configuration_is_not_exposed_and_cannot_block_source_readiness() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            fixture.commit("A.java", "class A {}\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            properties.getRepositories().get("orders").setProjectGuidePath("../private.md");
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                assertThat(registry.descriptors().getFirst().projectGuidePath()).isEmpty();
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), store, registry, properties);
                new SourcePreparationService(jobs, store, registry).prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob completed = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(completed.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                assertThat(store.manifest(completed.publication().orElseThrow().context(),
                        completed.publication().orElseThrow().manifestDigest()).projectGuide().state())
                        .isEqualTo(GuideState.INVALID);
            }
        }
    }

    @Test
    void invalid_raw_git_path_is_counted_but_invalid_excluded_descendants_count_as_files() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            org.eclipse.jgit.lib.Repository repo = fixture.git.getRepository();
            org.eclipse.jgit.lib.ObjectId revision;
            try (org.eclipse.jgit.lib.ObjectInserter objects = repo.newObjectInserter()) {
                org.eclipse.jgit.lib.ObjectId text = objects.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB,
                        "class Safe {}\n".getBytes(StandardCharsets.UTF_8));
                org.eclipse.jgit.lib.TreeFormatter nested = new org.eclipse.jgit.lib.TreeFormatter();
                nested.append("Nested.java", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, text);
                org.eclipse.jgit.lib.TreeFormatter excluded = new org.eclipse.jgit.lib.TreeFormatter();
                excluded.append(new byte[] {(byte) 0xfd, '.', 'j', 'a', 'v', 'a'},
                        org.eclipse.jgit.lib.FileMode.REGULAR_FILE, text);
                excluded.append(new byte[] {(byte) 0xfe}, org.eclipse.jgit.lib.FileMode.TREE,
                        objects.insert(nested));
                org.eclipse.jgit.lib.TreeFormatter tree = new org.eclipse.jgit.lib.TreeFormatter();
                tree.append("Safe.java", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, text);
                tree.append("target", org.eclipse.jgit.lib.FileMode.TREE, objects.insert(excluded));
                tree.append(new byte[] {(byte) 0xff, '.', 'j', 'a', 'v', 'a'},
                        org.eclipse.jgit.lib.FileMode.REGULAR_FILE, text);
                org.eclipse.jgit.lib.CommitBuilder commit = new org.eclipse.jgit.lib.CommitBuilder();
                commit.setTreeId(objects.insert(tree));
                org.eclipse.jgit.lib.PersonIdent author = new org.eclipse.jgit.lib.PersonIdent(
                        "Source fixture", "fixture@example.test");
                commit.setAuthor(author);
                commit.setCommitter(author);
                commit.setMessage("raw-path fixture");
                revision = objects.insert(commit);
                objects.flush();
            }
            org.eclipse.jgit.lib.RefUpdate head = repo.updateRef("refs/heads/main");
            head.setNewObjectId(revision);
            assertThat(head.update()).isIn(org.eclipse.jgit.lib.RefUpdate.Result.NEW,
                    org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD);
            try (DurableSourceFiles writer = new DurableSourceFiles(fixture.admin)) {
                RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, writer);
                SourcePublicationStore store = com.java.semantic.indexer.source.SourceLifecycleFixture.publications(properties, mapper, writer, jobs);
                RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
                RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                        new JGitRevisionExporter(resolver, registry, mapper), store, registry, properties);
                new SourcePreparationService(jobs, store, registry).prepareSource(fixture.repository,
                        new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
                SourcePreparationJob complete = manager.execute(jobs.claimNext().orElseThrow());
                assertThat(complete.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
                SourceRevisionManifest manifest = store.manifest(complete.publication().orElseThrow().context(),
                        complete.publication().orElseThrow().manifestDigest());
                assertThat(manifest.coverage().unsupportedCounts()).containsEntry(EntryStatus.UNSUPPORTED_PATH, 1L);
                assertThat(manifest.coverage().excludedFileCount()).isEqualTo(2);
                Path sealed = fixture.published.resolve("orders/revisions").resolve(revision.name());
                assertThat(Files.readString(sealed.resolve("inventory.jsonl"))).contains("Safe.java")
                        .doesNotContain("target", "�");
                assertThat(Files.readAllBytes(sealed.resolve("tree/Safe.java")))
                        .isEqualTo("class Safe {}\n".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

}
