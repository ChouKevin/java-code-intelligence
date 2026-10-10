package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.SourcePathResolver;
import com.java.semantic.query.source.SourceQueryException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static com.java.semantic.query.index.IndexPublicationReader.*;

/** Validates database claims against admitted inventory and only the affected source file. */
final class IndexRowValidator {
    // Internal evidence is not a public result. SQLite removes the unused signature before
    // JDBC materializes it; the remaining projection has its own source-sized byte ceiling.
    private static final int MAX_PARENT_EVIDENCE_BYTES = IndexContract.MAX_FILE_BYTES;

    private record ParentEvidence(String symbolId, String name, DeclarationKind kind, String owner,
            Optional<String> parentSymbolId, String path, String blobId, String contentDigest,
            long byteLength, SourceSpan range, SourceSpan nameRange) {
        private ParentEvidence {
            symbolId = ModelValidation.sha256(symbolId, "symbol identity");
            name = ModelValidation.requiredText(name, "declaration name");
            Objects.requireNonNull(kind, "declaration kind");
            validUnicode(name);
            validUnicode(owner);
            Objects.requireNonNull(parentSymbolId, "parent identity");
            parentSymbolId.ifPresent(value -> ModelValidation.sha256(value, "parent identity"));
            require(parentSymbolId.isEmpty() || !parentSymbolId.orElseThrow().equals(symbolId));
            path = SourcePathPolicy.requireFile(path);
            blobId = RepositoryRevision.ofSha(blobId).value();
            contentDigest = ModelValidation.sha256(contentDigest, "content digest");
            Objects.requireNonNull(range, "declaration range");
            Objects.requireNonNull(nameRange, "name range");
            require(byteLength >= 0 && byteLength <= IndexContract.MAX_FILE_BYTES && range.endByte() <= byteLength
                    && range.endByte() > range.startByte() && nameRange.endByte() > nameRange.startByte()
                    && range.contains(nameRange));
        }
    }

    private static void validUnicode(String value) {
        Objects.requireNonNull(value, "Unicode text");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                require(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)));
                index++;
            } else require(!Character.isLowSurrogate(character));
        }
    }
    private final ObjectMapper mapper;
    private final SourcePathResolver paths;
    private final AdmittedSourceRevision source;
    private final SourcePathResolver.InventoryLookup inventory;
    private final Connection connection;
    private final String generation;
    private final long deadline;
    private String cachedPath = "";
    private byte[] cachedBytes = new byte[0];
    private int[] cachedLines = {0};
    private FileOutcome cachedFile;

    IndexRowValidator(ObjectMapper mapper, SourcePathResolver paths, AdmittedSourceRevision source,
            SourcePathResolver.InventoryLookup inventory, Connection connection, String generation, long deadline) {
        this.mapper = mapper; this.paths = paths; this.source = source; this.inventory = inventory;
        this.connection = connection; this.generation = generation; this.deadline = deadline;
    }

    FileOutcome file(String path, boolean requested) {
        SourcePathResolver.safeFile(path);
        SourceInventoryEntry entry = inventory.find(path).orElseThrow(() -> requested
                ? new SourceQueryException(SourceQueryException.Code.SOURCE_NOT_FOUND)
                : new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE));
        if (requested && entry.kind() != EntryKind.FILE) throw new SourceQueryException(SourceQueryException.Code.SOURCE_NOT_FOUND);
        if (Objects.nonNull(cachedFile) && cachedFile.path().equals(path)) return cachedFile;
        try (PreparedStatement statement = connection.prepareStatement("SELECT path,java_file,status,byte_length,blob_id,content_digest,length(CAST(outcome_json AS BLOB)) AS bytes,CASE WHEN length(CAST(outcome_json AS BLOB))<=163840 THEN outcome_json ELSE NULL END AS payload FROM files WHERE path=?")) {
            statement.setString(1, path);
            try (ResultSet row = statement.executeQuery()) {
                require(row.next());
                if (row.getLong("bytes") > IndexContract.MAX_RESULT_BYTES) throw new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
                FileOutcome file = decode(row.getString("payload"), FileOutcome.class);
                require(file.path().equals(path) && file.javaFile() == (row.getInt("java_file") == 1)
                        && file.status().name().equals(row.getString("status")) && file.byteLength() == row.getLong("byte_length")
                        && Objects.equals(file.blobId().orElse(null), row.getString("blob_id"))
                        && Objects.equals(file.contentDigest().orElse(null), row.getString("content_digest"))
                        && file.byteLength() == entry.byteLength() && file.blobId().equals(entry.blobId()) && file.contentDigest().equals(entry.contentDigest()));
                if (file.status() == ParseStatus.PARSED || file.status() == ParseStatus.PARSE_FAILED) require(entry.status().orElseThrow() == EntryStatus.TEXT);
                for (Diagnostic diagnostic : file.diagnostics()) diagnostic.range().ifPresent(range -> span(range, bytes(entry)));
                cachedFile = file;
                return file;
            }
        } catch (SQLException failure) { check(deadline); throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
    }

    <T> T row(ResultSet row, Class<T> type) throws SQLException {
        T payload = decode(row.getString("bounded_payload"), type);
        if (payload instanceof Declaration declaration) {
            columns(row, declaration);
            declaration(declaration);
        } else if (payload instanceof EntryCandidate candidate) {
            Declaration declaration = candidate.declaration();
            require(candidate.candidateId().equals(row.getString("candidate_id")) && candidate.kind().name().equals(row.getString("kind"))
                    && declaration.symbolId().equals(row.getString("symbol_id")) && declaration.path().equals(row.getString("path"))
                    && candidate.evidence().range().startByte() == row.getLong("start_byte")
                    && candidate.candidateId().equals(IndexContract.identity(generation, declaration.symbolId(), candidate.kind().name(), Long.toString(candidate.evidence().range().startByte()))));
            try (PreparedStatement statement = connection.prepareStatement("SELECT symbol_id,path,name,kind,owner,parent_id,start_byte,name_start_byte,length(CAST(declaration_json AS BLOB)) AS bytes,CASE WHEN length(CAST(declaration_json AS BLOB))<=163840 THEN declaration_json ELSE NULL END AS payload FROM declarations WHERE symbol_id=?")) {
                statement.setString(1, declaration.symbolId());
                try (ResultSet linked = statement.executeQuery()) {
                    require(linked.next() && linked.getLong("bytes") <= IndexContract.MAX_RESULT_BYTES);
                    columns(linked, declaration);
                    require(decode(linked.getString("payload"), Declaration.class).equals(declaration));
                }
            }
            declaration(declaration);
            span(candidate.evidence().range(), cachedBytes);
        } else throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE);
        return payload;
    }

    Fold fold(ResultSet row, String path) throws SQLException {
        Fold fold = decode(row.getString("bounded_fold"), Fold.class);
        require(fold.declarationId().equals(row.getString("fold_id")) && path.equals(row.getString("fold_path"))
                && fold.kind().equals(row.getString("fold_kind")) && fold.interior().startByte() == row.getLong("fold_start"));
        Declaration declaration = row(row, Declaration.class);
        require(declaration.symbolId().equals(fold.declarationId()) && declaration.path().equals(path)
                && declaration.kind().name().equals(fold.kind()) && declaration.range().contains(fold.interior()));
        span(fold.interior(), cachedBytes);
        return fold;
    }

    private <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (RuntimeException failure) { throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
    }

    private static void columns(ResultSet row, Declaration declaration) throws SQLException {
        columns(row, declaration.symbolId(), declaration.path(), declaration.name(), declaration.kind(), declaration.owner(),
                declaration.parentSymbolId(), declaration.range(), declaration.nameRange());
    }

    private static void columns(ResultSet row, String symbolId, String path, String name, DeclarationKind kind,
            String owner, Optional<String> parentSymbolId, SourceSpan range, SourceSpan nameRange) throws SQLException {
        require(symbolId.equals(row.getString("symbol_id")) && path.equals(row.getString("path"))
                && name.equals(row.getString("name")) && kind.name().equals(row.getString("kind"))
                && owner.equals(row.getString("owner")) && Objects.equals(parentSymbolId.orElse(null), row.getString("parent_id"))
                && range.startByte() == row.getLong("start_byte") && nameRange.startByte() == row.getLong("name_start_byte"));
    }

    void declaration(Declaration declaration) throws SQLException {
        require(declaration.symbolId().equals(IndexContract.identity(generation, declaration.path(), declaration.kind().name(), Long.toString(declaration.nameRange().startByte()))));
        FileOutcome file = file(declaration.path(), false);
        require(file.status() == ParseStatus.PARSED && declaration.byteLength() == file.byteLength()
                && file.blobId().orElseThrow().equals(declaration.blobId()) && file.contentDigest().orElseThrow().equals(declaration.contentDigest()));
        byte[] bytes = bytes(inventory.find(declaration.path()).orElseThrow());
        sourceEvidence(declaration, bytes);
        if (declaration.parentSymbolId().isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "WITH parent_evidence AS MATERIALIZED ("
                    + "SELECT symbol_id,path,name,kind,owner,parent_id,start_byte,name_start_byte,"
                    + "json_remove(declaration_json,'$.signature') AS evidence FROM declarations WHERE symbol_id=?) "
                    + "SELECT symbol_id,path,name,kind,owner,parent_id,start_byte,name_start_byte,"
                    + "length(CAST(evidence AS BLOB)) AS bytes,"
                    + "CASE WHEN length(CAST(evidence AS BLOB))<=? THEN evidence ELSE NULL END AS payload FROM parent_evidence")) {
                statement.setString(1, declaration.parentSymbolId().orElseThrow());
                statement.setInt(2, MAX_PARENT_EVIDENCE_BYTES);
                try (ResultSet parent = statement.executeQuery()) {
                    require(parent.next() && parent.getLong("bytes") <= MAX_PARENT_EVIDENCE_BYTES);
                    ParentEvidence enclosing = decode(parent.getString("payload"), ParentEvidence.class);
                    columns(parent, enclosing.symbolId(), enclosing.path(), enclosing.name(), enclosing.kind(), enclosing.owner(),
                            enclosing.parentSymbolId(), enclosing.range(), enclosing.nameRange());
                    require(enclosing.path().equals(declaration.path()) && enclosing.range().contains(declaration.range())
                            && enclosing.symbolId().equals(IndexContract.identity(generation, enclosing.path(), enclosing.kind().name(), Long.toString(enclosing.nameRange().startByte())))
                            && enclosing.blobId().equals(declaration.blobId()) && enclosing.contentDigest().equals(declaration.contentDigest())
                            && enclosing.byteLength() == declaration.byteLength());
                    sourceEvidence(enclosing.range(), enclosing.nameRange(), enclosing.name(), bytes);
                }
            }
        }
    }

    Declaration symbol(String id, boolean requested) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT symbol_id,path,name,kind,owner,parent_id,start_byte,name_start_byte,length(CAST(declaration_json AS BLOB)) AS payload_bytes,CASE WHEN length(CAST(declaration_json AS BLOB))<=163840 THEN declaration_json ELSE NULL END AS bounded_payload FROM declarations WHERE symbol_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IndexFailure(requested ? IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND : IndexFailure.Code.INDEX_UNAVAILABLE);
                require(row.getLong("payload_bytes") <= IndexContract.MAX_RESULT_BYTES);
                return row(row, Declaration.class);
            }
        }
    }
    void fileSource(FileOutcome file) {
        if (file.status() == ParseStatus.PARSED || file.status() == ParseStatus.PARSE_FAILED) {
            bytes(inventory.find(file.path()).orElseThrow());
        }
    }


    void site(RelationContract.SourceSite site) {
        FileOutcome file = file(site.path(), false);
        require(file.status() == ParseStatus.PARSED && file.byteLength() == site.byteLength()
                && file.blobId().orElseThrow().equals(site.blobId()) && file.contentDigest().orElseThrow().equals(site.contentDigest()));
        byte[] bytes = bytes(inventory.find(site.path()).orElseThrow());
        span(site.range(), bytes);
        span(site.executionScope().range(), bytes);
    }

    private void sourceEvidence(Declaration declaration, byte[] bytes) {
        sourceEvidence(declaration.range(), declaration.nameRange(), declaration.name(), bytes);
    }

    private void sourceEvidence(SourceSpan range, SourceSpan nameRange, String name, byte[] bytes) {
        span(range, bytes);
        span(nameRange, bytes);
        String spelling = new String(bytes, Math.toIntExact(nameRange.startByte()),
                Math.toIntExact(nameRange.endByte() - nameRange.startByte()), StandardCharsets.UTF_8);
        require(unescape(spelling).equals(name));
    }

    private byte[] bytes(SourceInventoryEntry entry) {
        if (cachedPath.equals(entry.path())) return cachedBytes;
        try {
            Path path = paths.readable(source, entry);
            byte[] bytes = IndexPublicationReader.bytes(path, IndexContract.MAX_FILE_BYTES, deadline);
            if (bytes.length != entry.byteLength() || !digest(bytes).equals(entry.contentDigest().orElseThrow())) {
                throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
            }
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            int count = 1;
            for (int index = 0; index < bytes.length; index++) {
                if ((index & 8191) == 0) check(deadline);
                if (bytes[index] == '\n') count++;
            }
            int[] lines = new int[count];
            int next = 1;
            for (int index = 0; index < bytes.length; index++) {
                if ((index & 8191) == 0) check(deadline);
                if (bytes[index] == '\n') lines[next++] = index + 1;
            }
            cachedLines = lines;
            cachedPath = entry.path(); cachedBytes = bytes;
            return bytes;
        } catch (IndexFailure failure) {
            // This helper reads source bytes, not index artifacts; keep deadline failures unchanged.
            if (failure.code() != IndexFailure.Code.INDEX_UNAVAILABLE) throw failure;
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, failure);
        } catch (IOException failure) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, failure);
        }
    }

    private void span(SourceSpan range, byte[] bytes) {
        require(range.endByte() <= bytes.length);
        int start = Math.toIntExact(range.startByte()); int end = Math.toIntExact(range.endByte());
        require(boundary(bytes, start) && boundary(bytes, end));
        check(deadline);
        require(range.startLine() == line(start) && range.endLine() == line(Math.max(start, end - 1)));
    }
    private int line(int position) {
        int found = Arrays.binarySearch(cachedLines, position);
        return found >= 0 ? found + 1 : -found - 1;
    }
    private static boolean boundary(byte[] bytes, int position) { return position == bytes.length || (bytes[position] & 0xc0) != 0x80; }
    private static String unescape(String spelling) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < spelling.length(); index++) {
            char value = spelling.charAt(index);
            if (value == '\\' && index + 1 < spelling.length() && spelling.charAt(index + 1) == 'u') {
                int digits = index + 1;
                while (digits < spelling.length() && spelling.charAt(digits) == 'u') digits++;
                require(digits + 4 <= spelling.length());
                try { value = (char) Integer.parseInt(spelling.substring(digits, digits + 4), 16); }
                catch (NumberFormatException failure) { throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
                index = digits + 3;
            }
            result.append(value);
        }
        return result.toString();
    }
}
