package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentLocalPublisherTest {
    @TempDir Path temporary;
    static LocalDocumentPublisher.UpdateRequest request() {
        return new LocalDocumentPublisher.UpdateRequest(new SourceContext("sample", DocumentFixture.SOURCE_REVISION),
                List.of(), List.of("guide.md"), Optional.empty(), List.of());
    }
    static void identity(DocumentFixture fixture) throws Exception {
        fixture.git.getRepository().getConfig().setString("user", null, "name", "Fixture");
        fixture.git.getRepository().getConfig().setString("user", null, "email", "fixture@example.test");
        fixture.git.getRepository().getConfig().save();
    }
    @Test void emptyStatusIsReadOnlyAndDetachedHeadAndMissingIdentityAreUnsupported() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("empty"))) {
            assertThrows(LocalDocumentPublisher.Failure.class, () -> new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage()));
            identity(fixture);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertTrue(publisher.status().activeRevision().isEmpty());
                assertTrue(publisher.status().candidateRevision().isEmpty());
                assertFalse(Files.exists(fixture.publication));
                assertFalse(Files.exists(fixture.root.resolve("navigation-catalog.json")));
            }
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Detached\n"));
            fixture.git.checkout().setName(revision).call();
            assertEquals("LOCAL_DOCUMENT_UNSUPPORTED_REPOSITORY", assertThrows(LocalDocumentPublisher.Failure.class,
                    () -> new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())).code());
        }
    }
    @Test void selectiveRawUpdatePreservesUnrelatedChangesAndOldReads() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\nold\n", "repositories/sample/other.md", "# Other\n"));
            fixture.publish(a, a);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertFalse(publisher.update(request()).commitCreated());
                assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
            }
            Files.writeString(fixture.root.resolve("repositories/sample/other.md"), "unrelated dirty bytes");
            Files.writeString(fixture.root.resolve("untracked.txt"), "untracked bytes");
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\nnew\n");
            Path hookSentinel = fixture.root.resolve("hook-sentinel");
            Path filterSentinel = fixture.root.resolve("filter-sentinel");
            Path hook = fixture.root.resolve(".git/hooks/pre-commit");
            Files.writeString(hook, "#!/bin/sh\ntouch '" + hookSentinel + "'\n");
            hook.toFile().setExecutable(true);
            fixture.git.getRepository().getConfig().setString("filter", "trap", "clean", "touch '" + filterSentinel + "'; cat");
            fixture.git.getRepository().getConfig().save();
            Files.writeString(fixture.root.resolve(".gitattributes"), "*.md filter=trap\n");
            assertFalse(Files.exists(hookSentinel), "fixture must not execute the hook");
            assertFalse(Files.exists(filterSentinel), "fixture must not execute the clean filter");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                assertFalse(Files.exists(hookSentinel), "hook executed before " + phase);
                assertFalse(Files.exists(filterSentinel), "clean filter executed before " + phase);
            })) {
                LocalDocumentPublisher.Result result = publisher.update(request());
                String b = result.candidateRevision().orElseThrow();
                assertTrue(result.commitCreated());
                assertEquals(Optional.of(b), result.activeRevision());
                assertTrue(result.retainedRevisions().containsAll(List.of(a, b)));
                try (RevWalk walk = new RevWalk(fixture.git.getRepository())) {
                    assertEquals(a, walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(b)).getParent(0).name());
                }
                assertArrayEquals("# B\nnew\n".getBytes(StandardCharsets.UTF_8), blob(fixture, b, DocumentFixture.GUIDE));
                assertArrayEquals(blob(fixture, a, "repositories/sample/other.md"), blob(fixture, b, "repositories/sample/other.md"));
                assertEquals("unrelated dirty bytes", Files.readString(fixture.root.resolve("repositories/sample/other.md")));
                assertEquals("untracked bytes", Files.readString(fixture.root.resolve("untracked.txt")));
                assertFalse(Files.exists(hookSentinel), "publisher must never execute pre-commit");
                assertFalse(Files.exists(filterSentinel), "publisher must never execute clean filters during index publication");
                try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                    DocumentCatalog.CatalogView view = new DocumentSelectionLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader)
                            .load(Files.readAllBytes(fixture.publication), Map.of(), DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT));
                    assertEquals("# A\nold\n", view.revisions().get(a).documents().get(DocumentFixture.GUIDE).content());
                    assertEquals(2, view.revisions().get(b).documents().size());
                }
                assertFalse(publisher.update(request()).commitCreated());
                assertEquals(b, fixture.git.getRepository().resolve("HEAD").name());
            }
        }
    }
    @Test void rawUnrelatedPathsKeepDistinctBlobsAndIndexMetadata() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("raw-paths"))) {
            identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            byte[] firstPath = {(byte) 0xfe};
            byte[] secondPath = {(byte) 0xff};
            DirCacheEntry first = rawEntry(fixture, firstPath, "first unrelated blob");
            DirCacheEntry second = rawEntry(fixture, secondPath, "second unrelated blob");
            replaceIndexEntries(fixture, List.of(first, second), List.of());
            String a = fixture.git.commit().setMessage("Unrelated raw paths").call().name();
            fixture.publish(a, a);
            // Exercise extended index flags as well as the ordinary assume-valid bit.
            Process process = new ProcessBuilder("git", "update-index", "--skip-worktree", "-z", "--stdin")
                    .directory(fixture.root.toFile()).redirectErrorStream(true).start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(firstPath);
                input.write(0);
                input.write(secondPath);
                input.write(0);
            }
            String diagnostic = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), diagnostic);
            DirCache before = fixture.git.getRepository().readDirCache();
            DirCacheEntry expectedFirst = rawIndexEntry(before, firstPath);
            DirCacheEntry expectedSecond = rawIndexEntry(before, secondPath);
            assertTrue(expectedFirst.isSkipWorkTree());
            assertTrue(expectedSecond.isSkipWorkTree());
            assertTrue(expectedFirst.isAssumeValid());
            assertTrue(expectedSecond.isAssumeValid());
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentPublisher.Result result = publisher.update(request());
                assertTrue(result.commitCreated());
                String b = result.activeRevision().orElseThrow();
                try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                    DirCache tree = git.tree(Optional.of(ObjectId.fromString(b)));
                    assertEquals(before.getEntryCount(), tree.getEntryCount());
                    assertEquals(first.getObjectId(), rawIndexEntry(tree, firstPath).getObjectId());
                    assertEquals(second.getObjectId(), rawIndexEntry(tree, secondPath).getObjectId());
                    assertTrue(tree.findEntry(firstPath, firstPath.length) < tree.findEntry(secondPath, secondPath.length));
                }
                DirCache after = fixture.git.getRepository().readDirCache();
                assertEquals(before.getEntryCount(), after.getEntryCount());
                assertIndexMetadata(expectedFirst, rawIndexEntry(after, firstPath));
                assertIndexMetadata(expectedSecond, rawIndexEntry(after, secondPath));
                assertArrayEquals("# B\n".getBytes(StandardCharsets.UTF_8), blob(fixture, b, DocumentFixture.GUIDE));
                assertFalse(publisher.update(request()).commitCreated());
            }
        }
    }
    @Test void stagedRawPathRenameWithIdenticalBlobRefusesWithoutMutation() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("raw-staged"))) {
            identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            byte[] originalPath = {(byte) 0xff};
            DirCacheEntry original = rawEntry(fixture, originalPath, "unchanged blob");
            replaceIndexEntries(fixture, List.of(original), List.of());
            String a = fixture.git.commit().setMessage("Raw path before staged rename").call().name();
            fixture.publish(a, a);
            DirCacheEntry renamed = new DirCacheEntry(new byte[] {(byte) 0xfe});
            renamed.copyMetaData(original);
            replaceIndexEntries(fixture, List.of(renamed), List.of(originalPath));
            byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
            byte[] selection = Files.readAllBytes(fixture.publication);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Requested change\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals("LOCAL_DOCUMENT_DIRTY_INDEX", assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(request())).code());
            }
            assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
            assertArrayEquals(index, Files.readAllBytes(fixture.root.resolve(".git/index")));
            assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
            assertEquals("# Requested change\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
        }
    }
    @Test void executableCatalogSupportsNoopAndGuideUpdateWithoutChangingMode() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("executable-catalog"))) {
            identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            DirCacheEntry catalog = new DirCacheEntry(fixture.git.getRepository().readDirCache().getEntry("navigation-catalog.json"));
            catalog.setFileMode(FileMode.EXECUTABLE_FILE);
            replaceIndexEntries(fixture, List.of(catalog), List.of());
            String a = fixture.git.commit().setMessage("Executable catalog").call().name();
            fixture.publish(a, a);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentPublisher.Result noop = publisher.update(request());
                assertFalse(noop.commitCreated());
                assertEquals(Optional.of(a), noop.activeRevision());
                assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
                LocalDocumentPublisher.Result update = publisher.update(request());
                assertTrue(update.commitCreated());
                String b = update.activeRevision().orElseThrow();
                try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                    assertEquals(FileMode.EXECUTABLE_FILE, git.tree(Optional.of(ObjectId.fromString(b)))
                            .getEntry("navigation-catalog.json").getFileMode());
                }
                assertEquals(FileMode.EXECUTABLE_FILE, fixture.git.getRepository().readDirCache()
                        .getEntry("navigation-catalog.json").getFileMode());
                assertArrayEquals("# B\n".getBytes(StandardCharsets.UTF_8), blob(fixture, b, DocumentFixture.GUIDE));
            }
        }
    }
    @Test void symlinkModeCatalogStillRefusesBeforeMutation() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("symlink-catalog"))) {
            identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            DirCacheEntry catalog = new DirCacheEntry(fixture.git.getRepository().readDirCache().getEntry("navigation-catalog.json"));
            catalog.setFileMode(FileMode.SYMLINK);
            replaceIndexEntries(fixture, List.of(catalog), List.of());
            String a = fixture.git.commit().setMessage("Symlink mode catalog").call().name();
            byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals("LOCAL_DOCUMENT_CATALOG_EDITED", assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(request())).code());
            }
            assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
            assertArrayEquals(index, Files.readAllBytes(fixture.root.resolve(".git/index")));
            assertFalse(Files.exists(fixture.publication));
        }
    }
    static DirCacheEntry rawEntry(DocumentFixture fixture, byte[] path, String content) throws Exception {
        DirCacheEntry entry = new DirCacheEntry(path);
        entry.setFileMode(FileMode.REGULAR_FILE);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (ObjectInserter inserter = fixture.git.getRepository().newObjectInserter()) {
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, bytes));
            inserter.flush();
        }
        entry.setCreationTime(123456789000L);
        entry.setLastModified(Instant.ofEpochSecond(123456790, 123456789));
        entry.setLength(bytes.length);
        entry.setAssumeValid(true);
        return entry;
    }
    static void replaceIndexEntries(DocumentFixture fixture, List<DirCacheEntry> replacements, List<byte[]> removals) throws Exception {
        DirCache index = DirCache.lock(fixture.git.getRepository().getIndexFile(), fixture.git.getRepository().getFS());
        try {
            TreeMap<byte[], DirCacheEntry> entries = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < index.getEntryCount(); i++) {
                DirCacheEntry entry = index.getEntry(i);
                entries.put(entry.getRawPath(), entry);
            }
            for (byte[] path : removals) { entries.remove(path); }
            for (DirCacheEntry entry : replacements) { entries.put(entry.getRawPath(), entry); }
            DirCacheBuilder builder = index.builder();
            for (DirCacheEntry entry : entries.values()) { builder.add(entry); }
            assertTrue(builder.commit());
        } finally { index.unlock(); }
    }
    static DirCacheEntry rawIndexEntry(DirCache index, byte[] path) {
        int position = index.findEntry(path, path.length);
        assertTrue(position >= 0, "raw Git path must remain present: " + Arrays.toString(path));
        DirCacheEntry entry = index.getEntry(position);
        assertArrayEquals(path, entry.getRawPath());
        return entry;
    }
    static void assertIndexMetadata(DirCacheEntry expected, DirCacheEntry actual) {
        assertEquals(expected.getObjectId(), actual.getObjectId());
        assertEquals(expected.getFileMode(), actual.getFileMode());
        assertEquals(expected.getCreationTime(), actual.getCreationTime());
        assertEquals(expected.getLastModifiedInstant(), actual.getLastModifiedInstant());
        assertEquals(expected.getLength(), actual.getLength());
        assertEquals(expected.getStage(), actual.getStage());
        assertEquals(expected.isAssumeValid(), actual.isAssumeValid());
        assertEquals(expected.isSkipWorkTree(), actual.isSkipWorkTree());
        assertEquals(expected.isIntentToAdd(), actual.isIntentToAdd());
    }
    static byte[] blob(DocumentFixture fixture, String revision, String path) throws Exception {
        try (RevWalk walk = new RevWalk(fixture.git.getRepository());
                TreeWalk tree = TreeWalk.forPath(fixture.git.getRepository(), path, walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(revision)).getTree())) {
            return fixture.git.getRepository().open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes();
        }
    }
    @Test void stagedAndCatalogEditedRefusalsPreserveAllState() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(a, a);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Staged\n");
            fixture.git.add().addFilepattern(DocumentFixture.GUIDE).call();
            byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
            byte[] selection = Files.readAllBytes(fixture.publication);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals("LOCAL_DOCUMENT_DIRTY_INDEX", assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request())).code());
                assertArrayEquals(index, Files.readAllBytes(fixture.root.resolve(".git/index")));
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
                assertEquals("# Staged\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
            }
        }
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("dirty-catalog/repository"))) {
            identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            Files.writeString(fixture.root.resolve("navigation-catalog.json"), "edited");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals("LOCAL_DOCUMENT_CATALOG_EDITED", assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request())).code());
                assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
                assertEquals("edited", Files.readString(fixture.root.resolve("navigation-catalog.json")));
            }
        }
    }
    @Test void unsafeInputRemoteAndApprovalRefuseBeforeCommit() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                Files.write(fixture.root.resolve(DocumentFixture.GUIDE), new byte[] {(byte) 0xc3});
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request()));
                Files.write(fixture.root.resolve(DocumentFixture.GUIDE), new byte[DocumentContract.MAX_DOCUMENT_BYTES + 1]);
                assertEquals("DOCUMENT_CAPACITY_EXCEEDED", assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request())).code());
                Files.delete(fixture.root.resolve(DocumentFixture.GUIDE));
                Files.createSymbolicLink(fixture.root.resolve(DocumentFixture.GUIDE), temporary.resolve("outside"));
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request()));
                assertEquals(a, fixture.git.getRepository().resolve("HEAD").name());
            }
            fixture.git.getRepository().getConfig().setString("remote", "origin", "url", "file:///unused");
            fixture.git.getRepository().getConfig().save();
            assertThrows(LocalDocumentPublisher.Failure.class, () -> { try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) { publisher.status(); } });
        }
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("approved"))) {
            identity(fixture);
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Draft\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.APPROVED).storage())) {
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(request()));
                assertNull(fixture.git.getRepository().resolve("HEAD"));
                LocalDocumentPublisher.UpdateRequest draft = request();
                LocalDocumentPublisher.Result approved = publisher.update(new LocalDocumentPublisher.UpdateRequest(draft.primaryContext(),
                        draft.relatedContexts(), draft.fileNames(), Optional.of("operator review"), List.of()));
                assertTrue(approved.commitCreated());
                try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.APPROVED).storage(), new DocumentResultWriter(fixture.mapper))) {
                    assertEquals(DocumentContract.ReviewStatus.APPROVED, loader.load(approved.activeRevision().orElseThrow(),
                            DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT)).documents().get(DocumentFixture.GUIDE).reviewStatus());
                }
            }
        }
    }
}
