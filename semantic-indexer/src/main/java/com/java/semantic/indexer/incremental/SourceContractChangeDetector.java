package com.java.semantic.indexer.incremental;

import com.java.semantic.model.index.SymbolDocument;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares persisted declaration facts with deterministic syntax-only source declarations. */
@FunctionalInterface
public interface SourceContractChangeDetector {
    Impact detect(ChangedSource changedSource, List<SymbolDocument> persistedDeclarations);

    record Impact(boolean publicDeclaration, boolean inheritance, boolean frameworkAnnotation, boolean uncertain) {
        public static Impact bodyOrPrivateChange() { return new Impact(false, false, false, false); }
        public static Impact publicDeclarationChange() { return new Impact(true, false, false, false); }
        public static Impact inheritanceChange() { return new Impact(true, true, false, false); }
        public static Impact frameworkAnnotationChange() { return new Impact(true, false, true, false); }
        public static Impact uncertainChange() { return new Impact(false, false, false, true); }
    }

    static SourceContractChangeDetector lightweight() {
        return new LightweightDetector();
    }

    final class LightweightDetector implements SourceContractChangeDetector {
        private static final Pattern PUBLIC_DECLARATION = Pattern.compile("(?m)^\\s*(?:@[A-Za-z_$][\\w$.]*(?:\\([^\\n]*\\))?\\s*)*(?:(?:public|protected)\\s+)[^{;]+(?:\\{|;)");
        private static final Pattern INHERITANCE = Pattern.compile("\\b(?:extends|implements)\\b");
        private static final Pattern FRAMEWORK_ANNOTATION = Pattern.compile("@(?:[\\w$.]*\\.)?(?:Controller|Service|Component|Repository|Configuration|Bean|RequestMapping|GetMapping|PostMapping|KafkaListener|Scheduled)\\b");

        @Override
        public Impact detect(ChangedSource changedSource, List<SymbolDocument> persistedDeclarations) {
            ChangedSource requiredChange = Objects.requireNonNull(changedSource, "changed source is required");
            List<SymbolDocument> requiredDeclarations = List.copyOf(Objects.requireNonNull(persistedDeclarations,
                    "persisted declarations are required"));
            if (!requiredChange.newPath().endsWith(".java") || requiredChange.kind() == ChangeKind.ADD) {
                return Impact.publicDeclarationChange();
            }
            Set<String> oldDeclarations = declarationLines(requiredChange.oldContent());
            Set<String> newDeclarations = declarationLines(requiredChange.newContent());
            boolean persistedForSource = requiredDeclarations.stream()
                    .anyMatch(document -> document.range().sourceFile().equals(requiredChange.oldPath()));
            if (persistedForSource && oldDeclarations.isEmpty()) {
                return Impact.uncertainChange();
            }
            boolean contractChanged = !oldDeclarations.equals(newDeclarations);
            boolean inheritanceChanged = changed(INHERITANCE, requiredChange.oldContent(), requiredChange.newContent());
            boolean annotationChanged = changed(FRAMEWORK_ANNOTATION, requiredChange.oldContent(), requiredChange.newContent());
            return new Impact(contractChanged || inheritanceChanged || annotationChanged, inheritanceChanged, annotationChanged, false);
        }

        private static Set<String> declarationLines(String source) {
            java.util.TreeSet<String> declarations = new java.util.TreeSet<>();
            Matcher matcher = PUBLIC_DECLARATION.matcher(source);
            while (matcher.find()) {
                declarations.add(matcher.group().replaceAll("\\s+", " ").trim());
            }
            return Set.copyOf(declarations);
        }

        private static boolean changed(Pattern pattern, String oldSource, String newSource) {
            return !matches(pattern, oldSource).equals(matches(pattern, newSource));
        }

        private static Set<String> matches(Pattern pattern, String source) {
            java.util.TreeSet<String> matches = new java.util.TreeSet<>();
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                matches.add(matcher.group());
            }
            return Set.copyOf(matches);
        }
    }
}
