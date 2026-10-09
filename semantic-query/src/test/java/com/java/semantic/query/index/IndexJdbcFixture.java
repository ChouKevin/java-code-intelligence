package com.java.semantic.query.index;

import com.java.semantic.model.index.*;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.*;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Real documented JDBC schema, independent of Indexer and without mocked query rows. */
final class IndexJdbcFixture {
    final JsonMapper mapper = JsonMapper.builder().build();
    final SourceContext context;
    final Path root;
    final Path tree;
    final Path inventory;
    final Path lockRoot;
    final SourceRevisionManifest sourceManifest;
    final String sourceDigest;
    final List<Declaration> declarations = new ArrayList<>();
    final SourceInventoryEntry entry;
    IndexManifest manifest;
    Path database;
    private final Map<String, String> spellings;
    private final String text;

    IndexJdbcFixture(Path temporary, String revision, List<String> names, int signatureLength) throws Exception {
        this(temporary, revision, names, signatureLength, "\n", Map.of());
    }

    IndexJdbcFixture(Path temporary, String revision, List<String> names, int signatureLength,
            String lineEnding, Map<String, String> spellings) throws Exception {
        this(temporary, revision, names, signatureLength, spellings,
                names.stream().map(name -> spellings.getOrDefault(name, name))
                        .collect(java.util.stream.Collectors.joining(lineEnding, "", lineEnding)));
    }

    private IndexJdbcFixture(Path temporary, String revision, List<String> names, int signatureLength,
            Map<String, String> spellings, String text) throws Exception {
        this.spellings = Map.copyOf(spellings);
        this.text = text;
        context = new SourceContext("sample", revision);
        root = temporary.resolve("indexes"); tree = temporary.resolve("tree-" + revision);
        inventory = temporary.resolve("inventory-" + revision); lockRoot = temporary.resolve("source");
        Files.createDirectories(tree); Files.createDirectories(lockRoot.resolve("sample"));
        if (!Files.exists(lockRoot.resolve("sample/read.lock"))) Files.write(lockRoot.resolve("sample/read.lock"), new byte[0]);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(tree.resolve("Names.java"), bytes);
        entry = new SourceInventoryEntry("Names.java", EntryKind.FILE, Optional.of(EntryStatus.TEXT), bytes.length,
                Optional.of(revision), Optional.of(IndexPublicationReader.digest(bytes)));
        byte[] inventoryBytes = (mapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(inventory, inventoryBytes);
        sourceManifest = new SourceRevisionManifest(1, 2, context, Instant.EPOCH,
                new GuideInfo(GuideState.DISABLED, Optional.empty(), Optional.empty(), GuideFreshness.NOT_VERIFIED),
                new SourceRevisionManifest.Coverage(1, 0, Map.of()), IndexPublicationReader.digest(inventoryBytes));
        sourceDigest = IndexPublicationReader.digest(mapper.writeValueAsBytes(sourceManifest));
        publish(names, signatureLength);
    }

    void publish(List<String> names, int signatureLength) throws Exception {
        String generation = UUID.randomUUID().toString();
        Path directory = root.resolve("sample/revisions").resolve(context.revision()).resolve(generation);
        Files.createDirectories(directory); database = directory.resolve("index.sqlite"); declarations.clear();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE identity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), repository_id TEXT NOT NULL, revision TEXT NOT NULL, generation TEXT NOT NULL, source_manifest_digest TEXT NOT NULL, inventory_digest TEXT NOT NULL, profile_json TEXT NOT NULL)");
            sql.execute("CREATE TABLE files (path TEXT PRIMARY KEY, java_file INTEGER NOT NULL CHECK(java_file IN (0,1)), status TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>=0), blob_id TEXT, content_digest TEXT, outcome_json TEXT NOT NULL)");
            sql.execute("CREATE TABLE declarations (symbol_id TEXT PRIMARY KEY, path TEXT NOT NULL REFERENCES files(path), name TEXT NOT NULL COLLATE BINARY, kind TEXT NOT NULL, owner TEXT NOT NULL, parent_id TEXT REFERENCES declarations(symbol_id) DEFERRABLE INITIALLY DEFERRED, start_byte INTEGER NOT NULL, name_start_byte INTEGER NOT NULL, declaration_json TEXT NOT NULL)");
            sql.execute("CREATE INDEX declaration_name_order ON declarations(name COLLATE BINARY,path,name_start_byte,kind,symbol_id)");
            sql.execute("CREATE INDEX declaration_source_order ON declarations(path,start_byte,symbol_id)");
            sql.execute("CREATE TABLE candidates (candidate_id TEXT PRIMARY KEY, symbol_id TEXT NOT NULL REFERENCES declarations(symbol_id), kind TEXT NOT NULL, path TEXT NOT NULL REFERENCES files(path), start_byte INTEGER NOT NULL, candidate_json TEXT NOT NULL)");
            sql.execute("CREATE INDEX candidate_source_order ON candidates(path,start_byte,kind,candidate_id)");
            sql.execute("CREATE TABLE folds (symbol_id TEXT PRIMARY KEY REFERENCES declarations(symbol_id), path TEXT NOT NULL REFERENCES files(path), kind TEXT NOT NULL, start_byte INTEGER NOT NULL, fold_json TEXT NOT NULL)");
            sql.execute("CREATE INDEX fold_source_order ON folds(path,start_byte,symbol_id)");
            insert(connection, "INSERT INTO identity VALUES(1,?,?,?,?,?,?)", context.repositoryId(), context.revision(), generation, sourceDigest, sourceManifest.inventoryDigest(), mapper.writeValueAsString(IndexContract.currentProfile()));
            FileOutcome file = new FileOutcome(entry.path(), true, ParseStatus.PARSED, entry.byteLength(), entry.blobId(), entry.contentDigest(), List.of());
            insert(connection, "INSERT INTO files VALUES(?,?,?,?,?,?,?)", entry.path(), 1, "PARSED", entry.byteLength(), context.revision(), entry.contentDigest().orElseThrow(), mapper.writeValueAsString(file));
            int searchStart = 0;
            for (String name : names) {
                String spelling = spellings.getOrDefault(name, name);
                int position = text.indexOf(spelling, searchStart);
                long offset = text.substring(0, position).getBytes(StandardCharsets.UTF_8).length;
                int line = 1 + Math.toIntExact(text.substring(0, position).chars().filter(value -> value == '\n').count());
                long end = offset + spelling.getBytes(StandardCharsets.UTF_8).length;
                SourceSpan span = new SourceSpan(offset, end, line, line);
                Declaration declaration = new Declaration(IndexContract.identity(generation, entry.path(), "CLASS", Long.toString(offset)), name, DeclarationKind.CLASS,
                        "", Optional.empty(), entry.path(), context.revision(), entry.contentDigest().orElseThrow(), entry.byteLength(), span, span, "x".repeat(signatureLength));
                declarations.add(declaration);
                insert(connection, "INSERT INTO declarations VALUES(?,?,?,?,?,?,?,?,?)", declaration.symbolId(), entry.path(), name, "CLASS", "", null, offset, offset, mapper.writeValueAsString(declaration));
                searchStart = position + spelling.length();
            }
        }
        manifest = new IndexManifest(1, context, "f".repeat(64), generation, sourceDigest, sourceManifest.inventoryDigest(), IndexContract.currentProfile(),
                new Coverage(1, 1, 0, 0, 0, declarations.size(), true), Files.size(database), IndexPublicationReader.digest(Files.readAllBytes(database)), Instant.EPOCH);
        seal();
    }

    static IndexJdbcFixture callable(Path temporary, String revision, int trailingBlankLines) throws Exception {
        String text = "class A {\r\n void run() {\r\n  String s = \"漢\";\r\n }\r\n}\r\n" + "\r\n".repeat(trailingBlankLines);
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, revision, List.of("A", "run"), 0, Map.of(), text);
        Declaration original = fixture.declarations.getFirst();
        Declaration type = new Declaration(original.symbolId(), "A", DeclarationKind.CLASS, "", Optional.empty(),
                original.path(), original.blobId(), original.contentDigest(), original.byteLength(),
                new SourceSpan(0, original.byteLength() - 2L * (trailingBlankLines + 1), 1, 5), original.nameRange(), "class A");
        Declaration named = fixture.declarations.get(1);
        int start = text.indexOf("void");
        int closing = text.indexOf("}", start);
        long end = text.substring(0, closing + 1).getBytes(StandardCharsets.UTF_8).length;
        Declaration method = new Declaration(IndexContract.identity(fixture.manifest.indexGeneration(), named.path(), "METHOD",
                Long.toString(named.nameRange().startByte())), "run", DeclarationKind.METHOD, "A", Optional.of(type.symbolId()),
                named.path(), named.blobId(), named.contentDigest(), named.byteLength(),
                new SourceSpan(start, end, 2, 4), named.nameRange(), "void run()");
        long interiorStart = text.substring(0, text.indexOf("{", start) + 1).getBytes(StandardCharsets.UTF_8).length;
        long interiorEnd = text.substring(0, closing).getBytes(StandardCharsets.UTF_8).length;
        Fold fold = new Fold(method.symbolId(), "METHOD", new SourceSpan(interiorStart, interiorEnd, 2, 4));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            insert(connection, "DELETE FROM declarations");
            for (Declaration declaration : List.of(type, method)) {
                insert(connection, "INSERT INTO declarations VALUES(?,?,?,?,?,?,?,?,?)", declaration.symbolId(), declaration.path(),
                        declaration.name(), declaration.kind().name(), declaration.owner(), declaration.parentSymbolId().orElse(null),
                        declaration.range().startByte(), declaration.nameRange().startByte(), fixture.mapper.writeValueAsString(declaration));
            }
            insert(connection, "INSERT INTO folds VALUES(?,?,?,?,?)", fold.declarationId(), method.path(), fold.kind(),
                    fold.interior().startByte(), fixture.mapper.writeValueAsString(fold));
        }
        fixture.declarations.clear(); fixture.declarations.addAll(List.of(type, method));
        fixture.seal();
        return fixture;
    }

    void legacyProfile() throws Exception {
        Profile profile = new Profile(1, 1, "JavaParser", "3.28.2", "JAVA_21", true, 1);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insert(connection, "DROP TABLE folds");
            insert(connection, "UPDATE identity SET profile_json=?", mapper.writeValueAsString(profile));
        }
        manifest = new IndexManifest(manifest.formatVersion(), context, manifest.originFingerprint(), manifest.indexGeneration(),
                sourceDigest, sourceManifest.inventoryDigest(), profile, manifest.coverage(), manifest.databaseBytes(),
                manifest.databaseDigest(), manifest.preparedAt());
        seal();
    }

    static IndexJdbcFixture oversizedParent(Path temporary, String revision) throws Exception {
        String interfaces = java.util.stream.IntStream.range(0, 24_000)
                .mapToObj(index -> "I" + index).collect(java.util.stream.Collectors.joining(", "));
        String signature = "class Parent implements " + interfaces;
        String text = signature + " {\n    void child() {}\n}\n";
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, revision, List.of("Parent", "child"), 0,
                Map.of(), text);
        Declaration originalParent = fixture.declarations.getFirst();
        Declaration parent = new Declaration(originalParent.symbolId(), originalParent.name(), originalParent.kind(),
                "", Optional.empty(), originalParent.path(), originalParent.blobId(), originalParent.contentDigest(),
                originalParent.byteLength(), new SourceSpan(0, fixture.entry.byteLength() - 1, 1, 3),
                originalParent.nameRange(), signature);
        Declaration originalChild = fixture.declarations.get(1);
        long methodStart = originalChild.nameRange().startByte() - "void ".length();
        Declaration child = new Declaration(IndexContract.identity(fixture.manifest.indexGeneration(),
                fixture.entry.path(), "METHOD", Long.toString(originalChild.nameRange().startByte())),
                originalChild.name(), DeclarationKind.METHOD, "Parent", Optional.of(parent.symbolId()),
                originalChild.path(), originalChild.blobId(), originalChild.contentDigest(), originalChild.byteLength(),
                new SourceSpan(methodStart, methodStart + "void child() {}".length(), 2, 2), originalChild.nameRange(), "void child()");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            insert(connection, "DELETE FROM declarations");
            for (Declaration declaration : List.of(parent, child)) {
                insert(connection, "INSERT INTO declarations VALUES(?,?,?,?,?,?,?,?,?)", declaration.symbolId(),
                        declaration.path(), declaration.name(), declaration.kind().name(), declaration.owner(),
                        declaration.parentSymbolId().orElse(null), declaration.range().startByte(),
                        declaration.nameRange().startByte(), fixture.mapper.writeValueAsString(declaration));
            }
        }
        fixture.declarations.clear();
        fixture.declarations.addAll(List.of(parent, child));
        fixture.seal();
        return fixture;
    }

    void seal() throws Exception {
        manifest = new IndexManifest(manifest.formatVersion(), context, manifest.originFingerprint(), manifest.indexGeneration(), sourceDigest,
                sourceManifest.inventoryDigest(), manifest.profile(), manifest.coverage(), Files.size(database), IndexPublicationReader.digest(Files.readAllBytes(database)), Instant.EPOCH);
        byte[] bytes = mapper.writeValueAsBytes(manifest);
        Files.write(database.resolveSibling("manifest.json"), bytes);
        IndexRepositoryState.Receipt receipt = new IndexRepositoryState.Receipt(manifest, IndexPublicationReader.digest(bytes), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Path statePath = root.resolve("sample/state.json");
        Map<String, IndexRepositoryState.RevisionIndexes> revisions = new java.util.HashMap<>();
        if (Files.exists(statePath)) revisions.putAll(mapper.readValue(Files.readAllBytes(statePath), IndexRepositoryState.class).revisions());
        revisions.put(context.revision(), new IndexRepositoryState.RevisionIndexes(sourceDigest, Optional.of(manifest.indexGeneration()), Map.of(manifest.indexGeneration(), receipt)));
        IndexRepositoryState state = new IndexRepositoryState(1, "sample", manifest.originFingerprint(), revisions);
        Files.write(statePath, mapper.writeValueAsBytes(state));
    }

    AdmittedSourceRevision admit() { return admit(new SourceReadLocks(lockRoot)); }
    AdmittedSourceRevision admit(SourceReadLocks locks) {
        return new AdmittedSourceRevision(context, sourceManifest, sourceDigest, tree, inventory, locks.acquire(new RepositoryId("sample"), System.nanoTime() + 5_000_000_000L));
    }
    LocalIndexReader reader() { return new LocalIndexReader(root, mapper); }
    SearchCodeRequest search(String query, Match match, int limit, Optional<String> cursor) {
        return new SearchCodeRequest(context, query, match, List.of(), Optional.empty(), "", limit, cursor, Optional.empty());
    }
    static void insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
