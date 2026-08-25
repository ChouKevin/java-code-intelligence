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
        private static final Set<String> FRAMEWORK_ANNOTATIONS = Set.of(
                "Bean", "Component", "Configuration", "Controller", "ControllerAdvice", "DeleteMapping",
                "EventListener", "FeignClient", "GetMapping", "KafkaListener", "PatchMapping", "PostMapping",
                "PutMapping", "RabbitListener", "Repository", "RequestMapping", "RestController",
                "RestControllerAdvice", "Scheduled", "Service", "TransactionalEventListener");

        @Override
        public Impact detect(ChangedSource changedSource, List<SymbolDocument> persistedDeclarations) {
            ChangedSource requiredChange = Objects.requireNonNull(changedSource, "changed source is required");
            List<SymbolDocument> requiredDeclarations = List.copyOf(Objects.requireNonNull(persistedDeclarations,
                    "persisted declarations are required"));
            if (!javaSource(requiredChange)) {
                return Impact.publicDeclarationChange();
            }
            AnnotationExtraction oldAnnotations = annotationsOf(requiredChange.oldContent());
            AnnotationExtraction newAnnotations = annotationsOf(requiredChange.newContent());
            if (oldAnnotations.uncertain() || newAnnotations.uncertain()) {
                return Impact.uncertainChange();
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
            boolean annotationChanged = !oldAnnotations.contracts().equals(newAnnotations.contracts());
            return new Impact(contractChanged || inheritanceChanged || annotationChanged, inheritanceChanged, annotationChanged, false);
        }

        private static boolean javaSource(ChangedSource change) {
            return (change.newPath().isEmpty() ? change.oldPath() : change.newPath()).endsWith(".java");
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

        private static AnnotationExtraction annotationsOf(String source) {
            java.util.TreeSet<String> contracts = new java.util.TreeSet<>();
            int position = 0;
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current == '/' && position + 1 < source.length() && source.charAt(position + 1) == '/') {
                    position = skipLineComment(source, position + 2);
                    continue;
                }
                if (current == '/' && position + 1 < source.length() && source.charAt(position + 1) == '*') {
                    int commentEnd = source.indexOf("*/", position + 2);
                    if (commentEnd < 0) {
                        return AnnotationExtraction.uncertainResult();
                    }
                    position = commentEnd + 2;
                    continue;
                }
                if (current == '\"' || current == '\'') {
                    int literalEnd = skipLiteral(source, position, current);
                    if (literalEnd < 0) {
                        return AnnotationExtraction.uncertainResult();
                    }
                    position = literalEnd;
                    continue;
                }
                if (current != '@') {
                    position++;
                    continue;
                }
                AnnotationRead annotation = readAnnotation(source, position);
                if (annotation.uncertain()) {
                    return AnnotationExtraction.uncertainResult();
                }
                if (FRAMEWORK_ANNOTATIONS.contains(annotation.simpleName())) {
                    contracts.add(annotation.contract());
                }
                position = annotation.end();
            }
            return new AnnotationExtraction(Set.copyOf(contracts), false);
        }

        private static AnnotationRead readAnnotation(String source, int start) {
            int nameStart = start + 1;
            int position = nameStart;
            while (position < source.length() && annotationNameCharacter(source.charAt(position))) {
                position++;
            }
            if (position == nameStart) {
                return AnnotationRead.uncertainResult();
            }
            String name = source.substring(nameStart, position);
            int argumentsStart = skipWhitespaceAndComments(source, position);
            if (argumentsStart < 0) {
                return AnnotationRead.uncertainResult();
            }
            if (argumentsStart >= source.length() || source.charAt(argumentsStart) != '(') {
                return new AnnotationRead(simpleNameOf(name), name, position, false);
            }
            int argumentsEnd = matchingParenthesis(source, argumentsStart);
            if (argumentsEnd < 0) {
                return AnnotationRead.uncertainResult();
            }
            NormalizedAnnotation normalized = normalize(source.substring(start, argumentsEnd));
            if (normalized.uncertain()) {
                return AnnotationRead.uncertainResult();
            }
            return new AnnotationRead(simpleNameOf(name), normalized.value(), argumentsEnd, false);
        }

        private static int matchingParenthesis(String source, int start) {
            int depth = 0;
            int position = start;
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current == '\"' || current == '\'') {
                    int literalEnd = skipLiteral(source, position, current);
                    if (literalEnd < 0) {
                        return -1;
                    }
                    position = literalEnd;
                    continue;
                }
                if (current == '/' && position + 1 < source.length()
                        && (source.charAt(position + 1) == '/' || source.charAt(position + 1) == '*')) {
                    int commentEnd = skipComment(source, position);
                    if (commentEnd < 0) {
                        return -1;
                    }
                    position = commentEnd;
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                    if (depth == 0) {
                        return position + 1;
                    }
                }
                position++;
            }
            return -1;
        }

        private static int skipLineComment(String source, int position) {
            int lineEnd = source.indexOf('\n', position);
            return lineEnd < 0 ? source.length() : lineEnd + 1;
        }

        private static int skipComment(String source, int position) {
            if (source.charAt(position + 1) == '/') {
                return skipLineComment(source, position + 2);
            }
            int commentEnd = source.indexOf("*/", position + 2);
            return commentEnd < 0 ? -1 : commentEnd + 2;
        }

        private static int skipLiteral(String source, int start, char quote) {
            int position = start + 1;
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current == '\\') {
                    position += 2;
                    continue;
                }
                if (current == quote) {
                    return position + 1;
                }
                position++;
            }
            return -1;
        }

        private static boolean annotationNameCharacter(char value) {
            return Character.isJavaIdentifierPart(value) || value == '.' || value == '$';
        }

        private static int skipWhitespaceAndComments(String source, int position) {
            int result = position;
            while (result < source.length()) {
                while (result < source.length() && Character.isWhitespace(source.charAt(result))) {
                    result++;
                }
                if (result + 1 >= source.length() || source.charAt(result) != '/'
                        || (source.charAt(result + 1) != '/' && source.charAt(result + 1) != '*')) {
                    return result;
                }
                result = skipComment(source, result);
                if (result < 0) {
                    return -1;
                }
            }
            return result;
        }

        private static String simpleNameOf(String annotationName) {
            int separator = annotationName.lastIndexOf('.');
            return separator < 0 ? annotationName : annotationName.substring(separator + 1);
        }

        private static NormalizedAnnotation normalize(String annotation) {
            StringBuilder normalized = new StringBuilder();
            boolean quoted = false;
            char quote = 0;
            for (int position = 0; position < annotation.length(); position++) {
                char current = annotation.charAt(position);
                if (quoted) {
                    normalized.append(current);
                    if (current == '\\' && position + 1 < annotation.length()) {
                        normalized.append(annotation.charAt(++position));
                    } else if (current == quote) {
                        quoted = false;
                    }
                    continue;
                }
                if (current == '\"' || current == '\'') {
                    quoted = true;
                    quote = current;
                    normalized.append(current);
                } else if (current == '/' && position + 1 < annotation.length()
                        && (annotation.charAt(position + 1) == '/' || annotation.charAt(position + 1) == '*')) {
                    int commentEnd = skipComment(annotation, position);
                    if (commentEnd < 0) {
                        return NormalizedAnnotation.uncertainResult();
                    }
                    position = commentEnd - 1;
                } else if (!Character.isWhitespace(current)) {
                    normalized.append(current);
                }
            }
            return new NormalizedAnnotation(normalized.toString(), false);
        }

        private record AnnotationExtraction(Set<String> contracts, boolean uncertain) {
            private static AnnotationExtraction uncertainResult() {
                return new AnnotationExtraction(Set.of(), true);
            }
        }

        private record AnnotationRead(String simpleName, String contract, int end, boolean uncertain) {
            private static AnnotationRead uncertainResult() {
                return new AnnotationRead("", "", 0, true);
            }
        }

        private record NormalizedAnnotation(String value, boolean uncertain) {
            private static NormalizedAnnotation uncertainResult() {
                return new NormalizedAnnotation("", true);
            }
        }
    }
}
