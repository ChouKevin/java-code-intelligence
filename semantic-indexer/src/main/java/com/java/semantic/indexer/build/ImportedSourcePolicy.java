package com.java.semantic.indexer.build;

import com.java.semantic.model.source.SourceEvidencePolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts JDT's attested source plan into an exact, portable authorization policy. */
public final class ImportedSourcePolicy {
    private ImportedSourcePolicy() { }

    public static SourceEvidencePolicy from(FullIndexPlan plan, Optional<String> guidePath) {
        Objects.requireNonNull(plan, "source plan is required");
        if (!plan.importedInputs()) {
            throw new IllegalArgumentException("Git evidence requires imported source roots");
        }
        Path base = plan.repositoryRoot();
        List<String> roots = plan.sourceRoots().stream().map(path -> base.relativize(path).toString()
                .replace(path.getFileSystem().getSeparator(), "/")).map(root -> root.isEmpty() ? "." : root).toList();
        Set<String> selected = plan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath)
                .collect(Collectors.toUnmodifiableSet());
        return new SourceEvidencePolicy(SourceEvidencePolicy.VERSION, roots, selected, guidePath);
    }
}
