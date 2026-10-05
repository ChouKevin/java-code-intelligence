package com.java.semantic.query.source;

import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRepositoryState.*;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.query.config.SourceAccessProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

final class SourceFilesystemFixture {
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    final Path root;
    final Path tree;
    final Path inventory;
    final JsonMapper mapper = JsonMapper.builder().build();
    final SourceContext context = new SourceContext("sample", SHA);
    final List<SourceInventoryEntry> entries = new ArrayList<>();
    final SourceAccessProperties properties;
    final SourceReadLocks locks;
    private final LocalSourceRevisionCatalog catalog;

    SourceFilesystemFixture(Path temp) throws IOException {
        root = temp.resolve("published");
        tree = root.resolve("sample/revisions").resolve(SHA).resolve("tree");
        inventory = tree.getParent().resolve("inventory.jsonl");
        Files.createDirectories(tree);
        properties = new SourceAccessProperties(root, Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")),
                List.of("sample"), 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        Files.write(root.resolve("sample/read.lock"), new byte[0]);
        locks = new SourceReadLocks(root);
        catalog = new LocalSourceRevisionCatalog(properties, mapper, locks);
    }

    void file(String path, String text) throws IOException {
        Path target = tree.resolve(path);
        Files.createDirectories(target.getParent());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes);
        entries.add(new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(EntryStatus.TEXT), bytes.length,
                Optional.of(SHA), Optional.of(LocalSourceRevisionCatalog.digest(bytes))));
    }

    void directory(String path) {
        entries.add(new SourceInventoryEntry(path, EntryKind.DIRECTORY, Optional.empty(), 0, Optional.empty(), Optional.empty()));
    }

    void publish(Optional<String> guide) throws IOException {
        entries.sort((left, right) -> SourcePathResolver.compare(SourcePathResolver.key(left), SourcePathResolver.key(right)));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (SourceInventoryEntry entry : entries) {
            bytes.writeBytes(mapper.writeValueAsBytes(entry));
            bytes.write('\n');
        }
        Files.write(inventory, bytes.toByteArray());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        GuideInfo info = guide.isPresent() ? new GuideInfo(GuideState.AVAILABLE, guide,
                entries.stream().filter(item -> item.path().equals(guide.orElseThrow()))
                        .findFirst().orElseThrow().contentDigest(), GuideFreshness.NOT_VERIFIED)
                : new GuideInfo(GuideState.DISABLED, Optional.empty(), Optional.empty(), GuideFreshness.NOT_VERIFIED);
        SourceRevisionManifest manifest = new SourceRevisionManifest(1, SourceRevisionManifest.POLICY_VERSION, context, now, info,
                new SourceRevisionManifest.Coverage(entries.stream().filter(item -> item.status().equals(Optional.of(EntryStatus.TEXT))).count(),
                        0, Map.of()), LocalSourceRevisionCatalog.digest(bytes.toByteArray()));
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        Files.write(tree.getParent().resolve("manifest.json"), manifestBytes);
        String digest = LocalSourceRevisionCatalog.digest(manifestBytes);
        PreparedRevision receipt = new PreparedRevision(context, digest, "job-1", now);
        SourceRepositoryState state = new SourceRepositoryState(1, context.repositoryId(),
                Optional.of(new CurrentPublication(SHA, digest, "job-1", now)), Map.of(SHA, receipt),
                new PreparationStatus(PreparationPhase.COMPLETE, Optional.of("job-1"), Optional.empty()));
        Files.createDirectories(root.resolve("sample"));
        Files.write(root.resolve("sample/state.json"), mapper.writeValueAsBytes(state));
        Files.write(root.resolve("repositories.json"), mapper.writeValueAsBytes(List.of(
                new SourceRepositoryDescriptor("sample", "Sample", "main", guide))));
    }

    LocalRepositorySourceService service() { return new LocalRepositorySourceService(properties, mapper); }
    LocalSourceRevisionCatalog catalog() { return catalog; }
    AdmittedSourceRevision admit() { return catalog.admit(context); }
}
