package com.java.semantic.indexer.incremental;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/** JGit implementation that reads only the two supplied commit trees. */
public final class JGitRevisionDiffAdapter implements GitRevisionDiff {
    private static final int RENAME_SCORE = 60;
    private final Repository repository;

    public JGitRevisionDiffAdapter(Repository repository) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
    }

    @Override
    public List<ChangedSource> diff(String publishedRevision, String selectedRevision) {
        try (RevWalk walk = new RevWalk(repository); DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            RevCommit published = parseCommit(walk, publishedRevision);
            RevCommit selected = parseCommit(walk, selectedRevision);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
            return formatter.scan(published.getTree(), selected.getTree()).stream()
                    .map(entry -> changedSource(entry)).sorted(java.util.Comparator.comparing(JGitRevisionDiffAdapter::sortKey)).toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to diff exact Git revisions", exception);
        }
    }

    private RevCommit parseCommit(RevWalk walk, String revision) throws IOException {
        ObjectId objectId = repository.resolve(Objects.requireNonNull(revision, "revision is required"));
        if (objectId == null) { // cs-allow JGit returns null for an absent revision.
            throw new IllegalArgumentException("revision does not resolve: " + revision);
        }
        return walk.parseCommit(objectId);
    }

    private ChangedSource changedSource(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case ADD -> ChangedSource.add(entry.getNewPath(), content(entry.getNewId().toObjectId()));
            case MODIFY -> ChangedSource.modify(entry.getNewPath(), content(entry.getOldId().toObjectId()),
                    content(entry.getNewId().toObjectId()));
            case DELETE -> ChangedSource.delete(entry.getOldPath(), content(entry.getOldId().toObjectId()));
            case RENAME, COPY -> ChangedSource.rename(entry.getOldPath(), entry.getNewPath(), content(entry.getOldId().toObjectId()),
                    content(entry.getNewId().toObjectId()));
        };
    }

    private String content(ObjectId objectId) {
        try {
            ObjectLoader loader = repository.open(objectId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            loader.copyTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read Git blob", exception);
        }
    }

    private static String sortKey(ChangedSource source) {
        return source.newPath().isEmpty() ? source.oldPath() : source.newPath();
    }
}
