package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Streams authorized inventory into a single immutable, syntax-only generation. */
public final class SqliteIndexBuilder {
    private final ObjectMapper mapper;
    private final SemanticIndexParser parser;

    public SqliteIndexBuilder(ObjectMapper mapper, SemanticIndexParser parser) {
        this.mapper = Objects.requireNonNull(mapper);
        this.parser = Objects.requireNonNull(parser);
    }

    public IndexManifest build(IndexBuildInput input, Path staging, Runnable capacityCheck) {
        capacityCheck.run();
        try {
            Path stage = staging.toAbsolutePath().normalize();
            IndexFiles.safe(stage);
            if (!stage.getFileName().toString().equals(input.generation())
                    || Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) throw new IOException("generation staging is not fresh");
            IndexFiles.safe(input.tree());
            if (!Files.isDirectory(input.tree(), LinkOption.NOFOLLOW_LINKS)
                    || !IndexFiles.digest(input.inventory()).equals(input.sourceManifest().inventoryDigest()))
                throw new IOException("source inventory identity mismatch");
            byte[] sourceManifest = IndexFiles.read(input.tree().getParent().resolve("manifest.json"), IndexFiles.MAX_MANIFEST);
            if (!DurableSourceFiles.sha256(sourceManifest).equals(input.sourceManifestDigest())
                    || !mapper.readValue(sourceManifest, com.java.semantic.model.source.SourceRevisionManifest.class).equals(input.sourceManifest()))
                throw new IOException("source manifest mismatch");
            if (!stage.getParent().getFileName().toString().equals("staging")
                    || !stage.getParent().getParent().getFileName().toString().equals("index"))
                throw new IOException("index staging namespace mismatch");
            DurableSourceFiles.ensureDirectories(stage.getParent().getParent().getParent(), stage, DurableSourceFiles.Visibility.PRIVATE);
            Path database = stage.resolve("index.sqlite");
            long[] counts = new long[6];
            try (Connection connection = open(database)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=DELETE");
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA synchronous=FULL");
                    statement.execute("PRAGMA page_size=4096");
                    statement.execute("PRAGMA max_page_count=16384");
                }
                connection.setAutoCommit(false);
                schema(connection);
                try (PreparedStatement identity = connection.prepareStatement("INSERT INTO identity VALUES(1,?,?,?,?,?,?)")) {
                    identity.setString(1, input.context().repositoryId()); identity.setString(2, input.context().revision());
                    identity.setString(3, input.generation()); identity.setString(4, input.sourceManifestDigest());
                    identity.setString(5, input.sourceManifest().inventoryDigest()); identity.setString(6, mapper.writeValueAsString(IndexContract.currentProfile()));
                    identity.executeUpdate();
                }
                populate(connection, database, input, counts, capacityCheck);
                connection.commit();
            }
            capacityCheck.run();
            validateDatabase(database, input);
            noSidecars(database);
            long size = Files.size(database);
            if (size > IndexContract.MAX_DATABASE_BYTES) throw new IOException("database exceeds generation limit");
            Coverage coverage = new Coverage(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[0] == counts[1]);
            IndexManifest manifest = new IndexManifest(IndexContract.FORMAT_VERSION, input.context(), input.originFingerprint(),
                    input.generation(), input.sourceManifestDigest(), input.sourceManifest().inventoryDigest(),
                    IndexContract.currentProfile(), coverage, size, IndexFiles.digest(database), Instant.now());
            capacityCheck.run();
            DurableSourceFiles.atomicBytes(stage.resolve("manifest.json"), mapper.writeValueAsBytes(manifest),
                    IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.forceTree(stage);
            return manifest;
        } catch (SQLException | IOException exception) {
            throw IndexFiles.unavailable(exception);
        } catch (LinkageError failure) {
            throw IndexFailure.nativeUnavailable(failure);
        }
    }

    private static Connection open(Path database) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite driver unavailable", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static void schema(Connection connection) throws SQLException {
        List<String> sql = List.of(
                "CREATE TABLE identity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), repository_id TEXT NOT NULL, revision TEXT NOT NULL, generation TEXT NOT NULL, source_manifest_digest TEXT NOT NULL, inventory_digest TEXT NOT NULL, profile_json TEXT NOT NULL)",
                "CREATE TABLE files (path TEXT PRIMARY KEY, java_file INTEGER NOT NULL CHECK(java_file IN (0,1)), status TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>=0), blob_id TEXT, content_digest TEXT, outcome_json TEXT NOT NULL)",
                "CREATE TABLE declarations (symbol_id TEXT PRIMARY KEY, path TEXT NOT NULL REFERENCES files(path), name TEXT NOT NULL COLLATE BINARY, kind TEXT NOT NULL, owner TEXT NOT NULL, parent_id TEXT REFERENCES declarations(symbol_id) DEFERRABLE INITIALLY DEFERRED, start_byte INTEGER NOT NULL, name_start_byte INTEGER NOT NULL, declaration_json TEXT NOT NULL)",
                "CREATE TABLE folds (symbol_id TEXT PRIMARY KEY REFERENCES declarations(symbol_id), path TEXT NOT NULL REFERENCES files(path), kind TEXT NOT NULL, start_byte INTEGER NOT NULL, fold_json TEXT NOT NULL)",
                "CREATE INDEX fold_source_order ON folds(path,start_byte,symbol_id)",
                "CREATE INDEX declaration_name_order ON declarations(name COLLATE BINARY,path,name_start_byte,kind,symbol_id)",
                "CREATE INDEX declaration_source_order ON declarations(path,start_byte,symbol_id)",
                "CREATE TABLE candidates (candidate_id TEXT PRIMARY KEY, symbol_id TEXT NOT NULL REFERENCES declarations(symbol_id), kind TEXT NOT NULL, path TEXT NOT NULL REFERENCES files(path), start_byte INTEGER NOT NULL, candidate_json TEXT NOT NULL)",
                "CREATE INDEX candidate_source_order ON candidates(path,start_byte,kind,candidate_id)");
        try (Statement statement = connection.createStatement()) {
            for (String command : sql) statement.execute(command);
        }
    }

    private void populate(Connection connection, Path database, IndexBuildInput input, long[] counts, Runnable capacityCheck) throws IOException, SQLException {
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(input.inventory(), LinkOption.NOFOLLOW_LINKS));
                PreparedStatement files = connection.prepareStatement("INSERT INTO files VALUES(?,?,?,?,?,?,?)");
                PreparedStatement declarations = connection.prepareStatement("INSERT INTO declarations VALUES(?,?,?,?,?,?,?,?,?)");
                PreparedStatement candidates = connection.prepareStatement("INSERT INTO candidates VALUES(?,?,?,?,?,?)");
                PreparedStatement folds = connection.prepareStatement("INSERT INTO folds VALUES(?,?,?,?,?)")) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            byte[] previous = new byte[0];
            long processed = 0;
            int next;
            while ((next = stream.read()) >= 0) {
                if (next != '\n') {
                    if (frame.size() >= 65535) throw new IOException("inventory frame exceeds limit");
                    frame.write(next);
                    continue;
                }
                SourceInventoryEntry entry = mapper.readValue(frame.toByteArray(), SourceInventoryEntry.class);
                frame.reset();
                byte[] key = (entry.path() + (entry.kind() == EntryKind.DIRECTORY ? "/" : "")).getBytes(StandardCharsets.UTF_8);
                if (Arrays.compareUnsigned(previous, key) >= 0 || SourcePathPolicy.isExcluded(entry.path()))
                    throw new IOException("inventory order or authorization mismatch");
                previous = key;
                Path sourcePath = input.tree().resolve(entry.path());
                IndexFiles.safe(sourcePath);
                if (entry.kind() == EntryKind.DIRECTORY) {
                    if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("inventory directory missing");
                    continue;
                }
                boolean javaFile = entry.path().endsWith(".java");
                byte[] bytes = new byte[0];
                ParsedFile parsed;
                if (entry.status().orElseThrow() == EntryStatus.TEXT) {
                    if (Files.size(sourcePath) != entry.byteLength() || !IndexFiles.digest(sourcePath).equals(entry.contentDigest().orElseThrow()))
                        throw new IOException("source bytes differ from inventory");
                    if (javaFile && entry.byteLength() <= IndexContract.MAX_FILE_BYTES) {
                        bytes = IndexFiles.read(sourcePath, IndexContract.MAX_FILE_BYTES);
                        if (bytes.length != entry.byteLength() || !DurableSourceFiles.sha256(bytes).equals(entry.contentDigest().orElseThrow()))
                            throw new IOException("source changed during read");
                        parsed = parser.parse(input.generation(), entry, bytes);
                    } else {
                        parsed = outcome(entry, javaFile && entry.byteLength() > IndexContract.MAX_FILE_BYTES ? ParseStatus.TOO_LARGE : ParseStatus.UNSUPPORTED,
                                javaFile ? "TOO_LARGE" : "NOT_JAVA");
                    }
                } else {
                    if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsupported source has readable content");
                    parsed = outcome(entry, ParseStatus.UNSUPPORTED, javaFile ? "UNSUPPORTED" : "NOT_JAVA");
                }
                validateParsed(input.generation(), entry, parsed, bytes);
                FileOutcome file = parsed.file();
                files.setString(1, file.path()); files.setInt(2, javaFile ? 1 : 0); files.setString(3, file.status().name());
                files.setLong(4, file.byteLength()); files.setString(5, file.blobId().orElse(null)); files.setString(6, file.contentDigest().orElse(null));
                files.setString(7, mapper.writeValueAsString(file)); files.executeUpdate();
                if (javaFile) { counts[0]++; counts[1 + file.status().ordinal()]++; }
                counts[5] += parsed.declarations().size();
                if (counts[5] > IndexContract.MAX_DECLARATIONS) throw new IOException("declaration limit exceeded");
                for (Declaration declaration : parsed.declarations()) {
                    declarations.setString(1, declaration.symbolId()); declarations.setString(2, declaration.path()); declarations.setString(3, declaration.name());
                    declarations.setString(4, declaration.kind().name()); declarations.setString(5, declaration.owner()); declarations.setString(6, declaration.parentSymbolId().orElse(null));
                    declarations.setLong(7, declaration.range().startByte()); declarations.setLong(8, declaration.nameRange().startByte());
                    declarations.setString(9, mapper.writeValueAsString(declaration)); declarations.executeUpdate();
                }
                for (EntryCandidate candidate : parsed.candidates()) {
                    candidates.setString(1, candidate.candidateId()); candidates.setString(2, candidate.declaration().symbolId()); candidates.setString(3, candidate.kind().name());
                    candidates.setString(4, candidate.declaration().path()); candidates.setLong(5, candidate.evidence().range().startByte());
                    candidates.setString(6, mapper.writeValueAsString(candidate)); candidates.executeUpdate();
                }
                for (Fold fold : parsed.folds()) {
                    folds.setString(1, fold.declarationId()); folds.setString(2, file.path()); folds.setString(3, fold.kind());
                    folds.setLong(4, fold.interior().startByte()); folds.setString(5, mapper.writeValueAsString(fold));
                    folds.executeUpdate();
                }
                if (++processed % 32 == 0) {
                    capacityCheck.run();
                    if (Files.size(database) > IndexContract.MAX_DATABASE_BYTES) throw new IOException("database exceeds generation limit");
                }
            }
            if (frame.size() != 0) throw new IOException("inventory frame is not LF terminated");
        }
    }


    private static ParsedFile outcome(SourceInventoryEntry entry, ParseStatus status, String diagnostic) {
        return new ParsedFile(new FileOutcome(entry.path(), entry.path().endsWith(".java"), status, entry.byteLength(),
                entry.blobId(), entry.contentDigest(), List.of(new Diagnostic(diagnostic, Optional.empty()))), List.of(), List.of(), List.of());
    }

    private static void validateParsed(String generation, SourceInventoryEntry entry, ParsedFile parsed, byte[] bytes) throws IOException {
        FileOutcome file = parsed.file();
        if (!file.path().equals(entry.path()) || file.byteLength() != entry.byteLength()
                || !file.blobId().equals(entry.blobId()) || !file.contentDigest().equals(entry.contentDigest()))
            throw new IOException("parser source provenance mismatch");
        IndexSourceCoordinates coordinates = new IndexSourceCoordinates(bytes);
        Map<String, Declaration> byId = new HashMap<>();
        for (Diagnostic diagnostic : file.diagnostics()) {
            if (diagnostic.range().isPresent()) coordinates.validate(diagnostic.range().orElseThrow());
        }
        for (Declaration declaration : parsed.declarations()) {
            if (!declaration.symbolId().equals(IndexContract.identity(generation, entry.path(), declaration.kind().name(), Long.toString(declaration.nameRange().startByte()))))
                throw new IOException("declaration generation identity mismatch");
            coordinates.validate(declaration.range()); coordinates.validate(declaration.nameRange());
            String raw = new String(bytes, (int) declaration.nameRange().startByte(),
                    (int) (declaration.nameRange().endByte() - declaration.nameRange().startByte()), StandardCharsets.UTF_8);
            if (!decodeEscapes(raw).equals(declaration.name())) throw new IOException("name does not match raw source");
            if (Objects.nonNull(byId.put(declaration.symbolId(), declaration))) throw new IOException("duplicate declaration identity");
        }
        for (Declaration declaration : parsed.declarations()) {
            if (declaration.parentSymbolId().isPresent()) {
                Declaration parent = byId.get(declaration.parentSymbolId().orElseThrow());
                if (Objects.isNull(parent) || !parent.range().contains(declaration.range())
                        || parent.range().endByte() - parent.range().startByte() <= declaration.range().endByte() - declaration.range().startByte())
                    throw new IOException("invalid enclosing declaration");
            }
        }
        for (EntryCandidate candidate : parsed.candidates()) {
            if (!candidate.declaration().equals(byId.get(candidate.declaration().symbolId()))
                    || !candidate.candidateId().equals(IndexContract.identity(generation, candidate.declaration().symbolId(),
                    candidate.kind().name(), Long.toString(candidate.evidence().range().startByte()))))
                throw new IOException("candidate generation or declaration mismatch");
            coordinates.validate(candidate.evidence().range());
        }
        for (Fold fold : parsed.folds()) {
            Declaration declaration = byId.get(fold.declarationId());
            if (Objects.isNull(declaration) || !declaration.kind().name().equals(fold.kind())
                    || !declaration.range().contains(fold.interior())) throw new IOException("fold declaration mismatch");
            coordinates.validate(fold.interior());
        }
    }

    private static String decodeEscapes(String raw) throws IOException {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '\\' && index + 1 < raw.length() && raw.charAt(index + 1) == 'u') {
                int digit = index + 1;
                while (digit < raw.length() && raw.charAt(digit) == 'u') digit++;
                if (digit + 4 > raw.length()) throw new IOException("invalid Unicode escape in name");
                try { decoded.append((char) Integer.parseInt(raw.substring(digit, digit + 4), 16)); }
                catch (NumberFormatException exception) { throw new IOException("invalid Unicode escape in name", exception); }
                index = digit + 3;
            } else decoded.append(character);
        }
        return decoded.toString();
    }


    private void validateDatabase(Path database, IndexBuildInput input) throws SQLException, IOException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equals(integrity.getString(1)) || integrity.next()) throw new IOException("database integrity failure");
            }
            try (ResultSet keys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (keys.next()) throw new IOException("database foreign key failure");
            }
            try (ResultSet identity = statement.executeQuery("SELECT * FROM identity")) {
                if (!identity.next() || identity.getInt("singleton") != 1
                        || !identity.getString("repository_id").equals(input.context().repositoryId())
                        || !identity.getString("revision").equals(input.context().revision())
                        || !identity.getString("generation").equals(input.generation())
                        || !identity.getString("source_manifest_digest").equals(input.sourceManifestDigest())
                        || !identity.getString("inventory_digest").equals(input.sourceManifest().inventoryDigest())
                        || !mapper.readValue(identity.getString("profile_json"), Profile.class).equals(IndexContract.currentProfile())
                        || identity.next()) throw new IOException("database identity mismatch");
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM files")) {
                while (rows.next()) {
                    FileOutcome file = mapper.readValue(rows.getString("outcome_json"), FileOutcome.class);
                    if (!file.path().equals(rows.getString("path")) || file.javaFile() != (rows.getInt("java_file") == 1)
                            || !file.status().name().equals(rows.getString("status")) || file.byteLength() != rows.getLong("byte_length")
                            || !Objects.equals(file.blobId().orElse(null), rows.getString("blob_id"))
                            || !Objects.equals(file.contentDigest().orElse(null), rows.getString("content_digest"))) throw new IOException("file payload mismatch");
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM declarations")) {
                while (rows.next()) {
                    Declaration declaration = mapper.readValue(rows.getString("declaration_json"), Declaration.class);
                    if (!declaration.symbolId().equals(rows.getString("symbol_id")) || !declaration.path().equals(rows.getString("path"))
                            || !declaration.name().equals(rows.getString("name")) || !declaration.kind().name().equals(rows.getString("kind"))
                            || !declaration.owner().equals(rows.getString("owner")) || !Objects.equals(declaration.parentSymbolId().orElse(null), rows.getString("parent_id"))
                            || declaration.range().startByte() != rows.getLong("start_byte") || declaration.nameRange().startByte() != rows.getLong("name_start_byte"))
                        throw new IOException("declaration payload mismatch");
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM candidates")) {
                while (rows.next()) {
                    EntryCandidate candidate = mapper.readValue(rows.getString("candidate_json"), EntryCandidate.class);
                    if (!candidate.candidateId().equals(rows.getString("candidate_id")) || !candidate.declaration().symbolId().equals(rows.getString("symbol_id"))
                            || !candidate.kind().name().equals(rows.getString("kind")) || !candidate.declaration().path().equals(rows.getString("path"))
                            || candidate.evidence().range().startByte() != rows.getLong("start_byte")) throw new IOException("candidate payload mismatch");
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT f.*,d.declaration_json FROM folds f JOIN declarations d ON d.symbol_id=f.symbol_id")) {
                while (rows.next()) {
                    Fold fold = mapper.readValue(rows.getString("fold_json"), Fold.class);
                    Declaration declaration = mapper.readValue(rows.getString("declaration_json"), Declaration.class);
                    if (!fold.declarationId().equals(rows.getString("symbol_id")) || !fold.kind().equals(rows.getString("kind"))
                            || fold.interior().startByte() != rows.getLong("start_byte") || !declaration.path().equals(rows.getString("path"))
                            || !declaration.kind().name().equals(fold.kind()) || !declaration.range().contains(fold.interior()))
                        throw new IOException("fold payload mismatch");
                }
            }
        }
    }

    static void noSidecars(Path database) throws IOException {
        for (String suffix : List.of("-journal", "-wal", "-shm")) {
            if (Files.exists(database.resolveSibling(database.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("sealed database has a sidecar");
        }
    }
}
