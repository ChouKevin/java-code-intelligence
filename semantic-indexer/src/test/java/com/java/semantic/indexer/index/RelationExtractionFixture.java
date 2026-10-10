package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideFreshness;
import com.java.semantic.model.source.SourceReadContract.GuideInfo;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Controlled raw source inputs, never a build of an imported repository. */
final class RelationExtractionFixture implements AutoCloseable {
    final String generation = UUID.randomUUID().toString();
    final JavaSyntaxExtractor parser = new JavaSyntaxExtractor();
    final Map<String, ParsedJavaUnit> units = new LinkedHashMap<>();
    final Map<String, byte[]> bytes = new LinkedHashMap<>();
    final SourceResolutionSession session;

    RelationExtractionFixture(Path directory, Map<String, String> sources) throws IOException {
        this(directory, sources, List.of(), List.of(""));
    }

    RelationExtractionFixture(Path directory, Map<String, String> sources,
            List<ResolutionPlan.Dependency> dependencies, List<String> roots) throws IOException {
        Path tree = Files.createDirectories(directory.resolve("tree"));
        SourceContext context = new SourceContext("sample", "a".repeat(40));
        SourceRevisionManifest manifest = new SourceRevisionManifest(1, 2, context, Instant.EPOCH,
                new GuideInfo(GuideState.DISABLED, Optional.empty(), Optional.empty(), GuideFreshness.NOT_VERIFIED),
                new SourceRevisionManifest.Coverage(sources.size(), 0, Map.of()), "b".repeat(64));
        IndexBuildInput input = new IndexBuildInput(context, "c".repeat(64), generation, "d".repeat(64), manifest,
                tree, directory.resolve("inventory.jsonl"));
        RelationContract.Platform platform = new RelationContract.Platform(System.getProperty("java.version"),
                System.getProperty("java.vendor"), "e".repeat(64), "f".repeat(64));
        ResolutionPlan plan = new ResolutionPlan(1, context, input.originFingerprint(), input.sourceManifestDigest(),
                manifest.inventoryDigest(), IndexContract.relationsProfile(), platform, roots, dependencies);
        session = new SourceResolutionSession(input, plan, directory, parser);
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
            Path path = tree.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, content);
            SourceInventoryEntry metadata = new SourceInventoryEntry(entry.getKey(), EntryKind.FILE, Optional.of(EntryStatus.TEXT),
                    content.length, Optional.of("a".repeat(40)), Optional.of(DurableSourceFiles.sha256(content)));
            ParsedJavaUnit parsed = parser.parseUnit(generation, metadata, content);
            units.put(entry.getKey(), parsed);
            bytes.put(entry.getKey(), content);
            session.observe(parsed);
        }
        session.sealCatalogue();
    }

    List<RelationContract.Fact> extract() {
        List<RelationContract.Fact> facts = new ArrayList<>();
        JavaRelationExtractor extractor = new JavaRelationExtractor();
        for (Map.Entry<String, ParsedJavaUnit> entry : units.entrySet()) {
            if (entry.getValue().ast().isPresent() && session.inScope(entry.getKey()))
                extractor.extract(session.unit(entry.getKey()), session, facts::add);
        }
        return facts;
    }

    String slice(RelationContract.Fact fact) {
        byte[] content = bytes.get(fact.source().path());
        return new String(content, Math.toIntExact(fact.source().range().startByte()),
                Math.toIntExact(fact.source().range().endByte() - fact.source().range().startByte()), StandardCharsets.UTF_8);
    }

    @Override public void close() throws IOException { session.close(); }
}
