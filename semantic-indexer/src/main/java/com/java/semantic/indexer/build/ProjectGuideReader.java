package com.java.semantic.indexer.build;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideProvenance;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.json.JsonParseException;

/** Validates the entire exact Git guide blob; format failures are nonfatal, storage failures are not. */
public final class ProjectGuideReader {
    private static final Pattern FIRST_FENCE = Pattern.compile("(?m)^```([^\\r\\n]*)\\r?\\n([\\s\\S]*?)^```\\s*$");

    public ProjectGuideMembership read(RepositoryId repository, RepositoryRevision revision,
            Optional<String> configuredPath, Optional<GitSnapshotEntry> trackedEntry, long maxBytes) {
        Objects.requireNonNull(repository, "repository is required");
        Objects.requireNonNull(revision, "revision is required");
        if (configuredPath.isEmpty()) {
            return ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        }
        String path = configuredPath.orElseThrow();
        if (!SourceEvidencePolicy.validPath(path) || !path.endsWith(".md")) {
            return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
        }
        if (trackedEntry.isEmpty()) {
            return ProjectGuideMembership.unavailable(ProjectGuideState.ABSENT);
        }
        GitSnapshotEntry entry = trackedEntry.orElseThrow();
        if (!path.equals(entry.path()) || !List.of("100644", "100755").contains(entry.mode())
                || entry.contentStatus() != GitFileContentStatus.TEXT || entry.byteLength() > maxBytes) {
            return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
        }
        try {
            byte[] bytes = entry.bytes();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Matcher fence = FIRST_FENCE.matcher(text);
            if (!fence.find() || !fence.group(1).trim().equals("json")) {
                return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
            }
            Document json = Document.parse(fence.group(2));
            Document scope = json.get("sourceScope", Document.class);
            if (Objects.isNull(scope) || !Integer.valueOf(1).equals(json.getInteger("formatVersion"))
                    || !Integer.valueOf(1).equals(json.getInteger("promptVersion"))
                    || !repository.value().equals(json.getString("repositoryId"))) {
                return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
            }
            String generatedAt = json.getString("generatedAt");
            String analyzedRevision = json.getString("analyzedRevision");
            if (Objects.isNull(analyzedRevision)) {
                return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
            }
            if (Objects.isNull(generatedAt) || !generatedAt.endsWith("Z")
                    || !ZoneOffset.UTC.equals(OffsetDateTime.parse(generatedAt).getOffset())) {
                return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
            }
            ProjectGuideProvenance provenance = new ProjectGuideProvenance(1, 1, repository,
                    RepositoryRevision.ofSha(analyzedRevision), Instant.parse(generatedAt),
                    new ProjectGuideProvenance.SourceScope(strings(scope, "includedPaths"),
                            strings(scope, "excludedPaths"), strings(scope, "limitations")));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new ProjectGuideMembership(ProjectGuideState.AVAILABLE, Optional.of(path),
                    Optional.of(HexFormat.of().formatHex(digest.digest(bytes))), Optional.of(revision),
                    Optional.of(provenance), "NOT_VERIFIED");
        } catch (JsonParseException | DateTimeParseException | IllegalArgumentException | ClassCastException
                | CharacterCodingException exception) {
            return ProjectGuideMembership.unavailable(ProjectGuideState.INVALID);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<String> strings(Document scope, String field) {
        Object value = scope.get(field);
        if (!(value instanceof List<?> values) || values.stream().anyMatch(element -> !(element instanceof String))) {
            throw new IllegalArgumentException("invalid guide provenance source scope");
        }
        return values.stream().map(String.class::cast).toList();
    }
}
